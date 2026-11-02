package com.nexus.commerce.service.impl;

import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderStatusHistory;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderItemRepository;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.OrderService;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt nghiệp vụ quản lý vòng đời đơn hàng trong hệ thống Nexus (Issue HG-S1-4 / SRS §3.4.3).
 *
 * <p>Chức năng:
 * <ul>
 *   <li>Quản lý truy vấn lịch sử đơn hàng của Người mua và Người bán có lọc trạng thái, ngày tháng và phân trang.</li>
 *   <li>Kiểm tra phân quyền truy cập đa người dùng (Multi-tenant Ownership Isolation) cho API xem chi tiết.</li>
 *   <li>Điều phối chuyển trạng thái đơn hàng an toàn qua máy trạng thái OrderStatusStateMachine.</li>
 *   <li>Ghi vết kiểm toán bất biến (Immutable Audit Trail) vào bảng order_status_history.</li>
 *   <li>Hỗ trợ tính lũy kế và hủy đơn chưa thu tiền; chưa có provider hoàn tiền nên không ghi REFUNDED mới.</li>
 * </ul>
 *
 * <p>Quy tắc và lý do:
 * <ul>
 *   <li>Toàn bộ phương thức đọc sử dụng @Transactional(readOnly = true) để tối ưu hiệu năng kết nối DB.</li>
 *   <li>Các thao tác cập nhật trạng thái (updateOrderStatus, cancelOrder) thực thi trong @Transactional để đảm bảo
 *       tính toàn vẹn dữ liệu giữa entity Order và OrderStatusHistory.</li>
 *   <li>Giới hạn độ dài chuỗi reason trong order_status_history tối đa 500 ký tự theo đúng Flyway V2 schema.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final int MAX_REASON_LENGTH = 500;

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderStatusStateMachine orderStatusStateMachine;
    private final OrderMapper orderMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * Lấy danh sách lịch sử đơn hàng của người mua.
     *
     * <p>Luồng xử lý:
     * <ol>
     *   <li>Kiểm tra tính hợp lệ của mốc thời gian: startDate không được sau endDate.</li>
     *   <li>Chuẩn hóa biên thời gian nếu chỉ truyền một trong hai mốc.</li>
     *   <li>Lựa chọn phương thức truy vấn tối ưu từ OrderRepository dựa trên tổ hợp điều kiện status và date range.</li>
     *   <li>Chuyển đổi Page&lt;Order&gt; sang Page&lt;OrderSummaryResponse&gt; thông qua OrderMapper.</li>
     * </ol>
     */
    @Override
    public Page<OrderSummaryResponse> getBuyerOrders(
            UUID buyerId,
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    ) {
        validateDateRange(startDate, endDate);

        boolean hasDateFilter = (startDate != null || endDate != null);
        Instant effectiveStart = (startDate != null) ? startDate : Instant.EPOCH;
        Instant effectiveEnd = (endDate != null) ? endDate : Instant.now();

        Page<Order> orderPage;
        if (status != null && hasDateFilter) {
            orderPage = orderRepository.findByBuyerIdAndStatusAndCreatedAtBetween(
                    buyerId, status, effectiveStart, effectiveEnd, pageable
            );
        } else if (status != null) {
            orderPage = orderRepository.findByBuyerIdAndStatus(buyerId, status, pageable);
        } else if (hasDateFilter) {
            orderPage = orderRepository.findByBuyerIdAndCreatedAtBetween(
                    buyerId, effectiveStart, effectiveEnd, pageable
            );
        } else {
            orderPage = orderRepository.findByBuyerId(buyerId, pageable);
        }

        return orderPage.map(orderMapper::toSummaryResponse);
    }

    /**
     * Xem thông tin chi tiết của đơn hàng kèm kiểm tra quyền sở hữu chặt chẽ.
     *
     * <p>Quy tắc phân quyền:
     * <ul>
     *   <li>Người dùng có vai trò ADMIN được xem toàn quyền mọi đơn hàng.</li>
     *   <li>Người mua chỉ được xem nếu order.buyerId == currentUserId.</li>
     *   <li>Người bán chỉ được xem nếu order.sellerId == currentUserId.</li>
     *   <li>Vi phạm sẽ ném BusinessException mã AUTH_FORBIDDEN (HTTP 403).</li>
     * </ul>
     */
    @Override
    public OrderDetailResponse getOrderDetail(
            UUID orderId,
            UUID currentUserId,
            List<String> userRoles
    ) {
        Order order = findOrderById(orderId);

        boolean isAdmin = hasRole(userRoles, OrderStatusStateMachine.ROLE_ADMIN);
        boolean isBuyerOwner = currentUserId != null && currentUserId.equals(order.getBuyerId());
        boolean isSellerOwner = currentUserId != null && currentUserId.equals(order.getSellerId());

        if (!isAdmin && !isBuyerOwner && !isSellerOwner) {
            log.warn("Từ chối truy cập đơn hàng {}: userId={}, buyerId={}, sellerId={}, roles={}",
                    orderId, currentUserId, order.getBuyerId(), order.getSellerId(), userRoles);
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn không có quyền truy cập vào thông tin chi tiết đơn hàng này"
            );
        }

        List<OrderStatusHistory> history = orderStatusHistoryRepository
                .findByOrderIdOrderByChangedAtAsc(orderId);
        if (history != null && !history.isEmpty()) {
            order.setStatusHistory(history);
        }

        return orderMapper.toDetailResponse(order);
    }

    /**
     * Lấy danh sách đơn hàng thuộc về một người bán cụ thể (scoping theo sellerId).
     *
     * <p>Luồng xử lý:
     * Điều hướng truy vấn Spring Data JPA theo tổ hợp bộ lọc status và khoảng thời gian tạo đơn.
     */
    @Override
    public Page<OrderSummaryResponse> getSellerOrders(
            UUID sellerId,
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    ) {
        validateDateRange(startDate, endDate);

        boolean hasDateFilter = (startDate != null || endDate != null);
        Instant effectiveStart = (startDate != null) ? startDate : Instant.EPOCH;
        Instant effectiveEnd = (endDate != null) ? endDate : Instant.now();

        Page<Order> orderPage;
        if (status != null && hasDateFilter) {
            orderPage = orderRepository.findBySellerIdAndStatusAndCreatedAtBetween(
                    sellerId, status, effectiveStart, effectiveEnd, pageable
            );
        } else if (status != null) {
            orderPage = orderRepository.findBySellerIdAndStatus(sellerId, status, pageable);
        } else if (hasDateFilter) {
            orderPage = orderRepository.findBySellerIdAndCreatedAtBetween(
                    sellerId, effectiveStart, effectiveEnd, pageable
            );
        } else {
            orderPage = orderRepository.findBySellerId(sellerId, pageable);
        }

        return orderPage.map(orderMapper::toSummaryResponse);
    }

    /**
     * Lấy danh sách toàn bộ đơn hàng trong hệ thống dành cho Quản trị viên (có phân trang và bộ lọc).
     *
     * <p>Chức năng:
     * Cung cấp danh sách đơn hàng toàn hệ thống với khả năng lọc theo trạng thái và khoảng thời gian tạo đơn,
     * phục vụ màn hình quản trị đơn hàng tập trung (Admin Dashboard).
     *
     * <p>Đầu vào và kết quả:
     * Nhận trạng thái status, khoảng thời gian UTC startDate/endDate và pageable. Trả về Page&lt;OrderSummaryResponse&gt;.
     *
     * <p>Luồng xử lý:
     * Kiểm tra khoảng thời gian -&gt; lựa chọn phương thức repository tối ưu từ OrderRepository -&gt; ánh xạ qua OrderMapper.
     */
    @Override
    public Page<OrderSummaryResponse> getAdminOrders(
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    ) {
        validateDateRange(startDate, endDate);

        boolean hasDateFilter = (startDate != null || endDate != null);
        Instant effectiveStart = (startDate != null) ? startDate : Instant.EPOCH;
        Instant effectiveEnd = (endDate != null) ? endDate : Instant.now();

        Page<Order> orderPage;
        if (status != null && hasDateFilter) {
            orderPage = orderRepository.findByStatusAndCreatedAtBetween(
                    status, effectiveStart, effectiveEnd, pageable
            );
        } else if (status != null) {
            orderPage = orderRepository.findByStatus(status, pageable);
        } else if (hasDateFilter) {
            orderPage = orderRepository.findByCreatedAtBetween(
                    effectiveStart, effectiveEnd, pageable
            );
        } else {
            orderPage = orderRepository.findAll(pageable);
        }

        return orderPage.map(orderMapper::toSummaryResponse);
    }

    /**
     * Cập nhật trạng thái đơn hàng kèm xác thực ma trận máy trạng thái và ghi audit log.
     *
     * <p>Đặc tính kỹ thuật:
     * <ol>
     *   <li>Kiểm tra quyền sở hữu gian hàng: Nếu là Người bán (không phải Admin), bắt buộc order.sellerId == currentUserId.</li>
     *   <li>Kiểm tra Idempotent: Nếu trạng thái hiện tại đã là trạng thái đích, trả về kết quả thành công mà không ghi trùng lịch sử.</li>
     *   <li>Xác thực chuyển đổi trạng thái bằng OrderStatusStateMachine.validateTransition(current, target, roles).</li>
     *   <li>Cập nhật Order.status và lưu trữ trong cùng transaction.</li>
     *   <li>Ghi nhận bản ghi OrderStatusHistory với reason tiền tố [ROLE] lý_do (cắt ngắn nếu quá 500 ký tự).</li>
     * </ol>
     */
    @Override
    @Transactional
    public OrderDetailResponse updateOrderStatus(
            UUID orderId,
            UpdateOrderStatusRequest request,
            UUID currentUserId,
            List<String> userRoles
    ) {
        Order order = findOrderForUpdate(orderId);
        OrderStatus currentStatus = order.getStatus();
        OrderStatus targetStatus = request.status();

        // 1. Kiểm soát phạm vi gian hàng đối với người bán (Tenant Isolation)
        boolean isAdmin = hasRole(userRoles, OrderStatusStateMachine.ROLE_ADMIN);
        boolean isSeller = hasRole(userRoles, OrderStatusStateMachine.ROLE_SELLER);
        if (isSeller && !isAdmin && !order.getSellerId().equals(currentUserId)) {
            log.warn("Người bán {} cố gắng cập nhật trạng thái đơn hàng {} của gian hàng {}",
                    currentUserId, orderId, order.getSellerId());
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Bạn chỉ có quyền cập nhật đơn hàng thuộc gian hàng của mình"
            );
        }

        rejectActiveVnpayCancellation(order, targetStatus);
        // 2. Kiểm tra tính Idempotent (chống trùng lặp yêu cầu mạng)
        if (currentStatus == targetStatus) {
            log.info("Đơn hàng {} hiện đã ở trạng thái {}, xử lý idempotent trả về kết quả hiện tại",
                    orderId, targetStatus);
            return orderMapper.toDetailResponse(order);
        }

        // Chưa có provider refund: không cho API đánh dấu đã hoàn tiền khi chưa hoàn tiền thật.
        if (targetStatus == OrderStatus.REFUNDED) { throw refundUnavailable(); }
        // VNPAY chỉ được xác nhận PAID qua IPN đã ký, kể cả khi actor là admin.
        if (targetStatus == OrderStatus.PAID && "VNPAY".equals(order.getPaymentMethod())) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Thanh toán VNPAY chỉ xác nhận qua IPN");
        }

        // 3. Xác thực tính hợp lệ của chuyển đổi qua máy trạng thái
        orderStatusStateMachine.validateTransition(currentStatus, targetStatus, userRoles);

        // 4. Cập nhật thực thể đơn hàng
        order.setStatus(targetStatus);
        Order savedOrder = orderRepository.save(order);
        if (targetStatus == OrderStatus.CANCELLED) { enqueueRelease(order); }

        // 5. Định dạng lý do kèm vai trò người thực hiện: [ROLE] Lý do
        String primaryActor = resolvePrimaryActor(userRoles);
        String formattedReason = formatAuditReason(primaryActor, request.reason(), targetStatus);

        // 6. Ghi vết kiểm toán bất biến
        OrderStatusHistory historyRecord = OrderStatusHistory.builder()
                .order(savedOrder)
                .fromStatus(currentStatus)
                .toStatus(targetStatus)
                .reason(formattedReason)
                .changedAt(Instant.now())
                .build();
        orderStatusHistoryRepository.save(historyRecord);
        savedOrder.addStatusHistory(historyRecord);

        log.info("Cập nhật thành công đơn hàng {}: {} -> {} bởi actor=[{}]",
                orderId, currentStatus, targetStatus, primaryActor);

        return orderMapper.toDetailResponse(savedOrder);
    }

    /**
     * Hủy đơn hàng an toàn theo yêu cầu của Người mua hoặc Quản trị viên.
     *
     * <p>Quy tắc nghiệp vụ:
     * <ul>
     *   <li>Chỉ người mua là chủ đơn hàng hoặc Admin hệ thống mới có quyền hủy đơn.</li>
     *   <li>Xử lý idempotent an toàn nếu đơn hàng đã ở trạng thái CANCELLED.</li>
     *   <li>Chỉ hủy đơn chưa thu tiền và không có VNPAY đang chờ; đơn PAID bị từ chối khi chưa có refund provider.</li>
     *   <li>Lưu lý do hủy và tác vụ release kho cùng transaction; worker retry sau commit bằng service account.</li>
     * </ul>
     */
    @Override
    @Transactional
    public OrderDetailResponse cancelOrder(
            UUID orderId,
            CancelOrderRequest request,
            UUID currentUserId,
            List<String> userRoles
    ) {
        Order order = findOrderForUpdate(orderId);
        OrderStatus currentStatus = order.getStatus();

        // 1. Kiểm tra thẩm quyền hủy đơn (Buyer sở hữu đơn hoặc Admin)
        boolean isAdmin = hasRole(userRoles, OrderStatusStateMachine.ROLE_ADMIN);
        boolean isBuyerOwner = currentUserId != null && currentUserId.equals(order.getBuyerId());

        if (!isAdmin && !isBuyerOwner) {
            log.warn("Từ chối yêu cầu hủy đơn {}: userId={}, buyerId={}, roles={}",
                    orderId, currentUserId, order.getBuyerId(), userRoles);
            throw new BusinessException(
                    ErrorCode.AUTH_FORBIDDEN,
                    "Chỉ người mua sở hữu đơn hàng hoặc Quản trị viên mới có quyền hủy đơn hàng"
            );
        }

        // 2. Tính lũy nghiệm: nếu đơn đã bị hủy hoặc hoàn tiền trước đó thì trả về ngay
        if (currentStatus == OrderStatus.CANCELLED || currentStatus == OrderStatus.REFUNDED) {
            log.info("Đơn hàng {} hiện đã ở trạng thái {}, xử lý idempotent trả về kết quả hiện tại",
                    orderId, currentStatus);
            return orderMapper.toDetailResponse(order);
        }

        rejectActiveVnpayCancellation(order, OrderStatus.CANCELLED);
        // Chỉ hủy đơn chưa thanh toán; PAID giữ nguyên để xử lý refund riêng.
        OrderStatus targetStatus;
        if (currentStatus == OrderStatus.PENDING_PAYMENT) {
            targetStatus = OrderStatus.CANCELLED;
        } else if (currentStatus == OrderStatus.PAID) {
            // Giữ PAID khi chưa có refund provider; không ghi nhận hoàn tiền giả.
            throw refundUnavailable();
        } else {
            log.warn("Không thể hủy đơn hàng {} khi đang ở trạng thái {}", orderId, currentStatus);
            throw new BusinessException(
                    ErrorCode.ORDER_CANNOT_CANCEL,
                    "Không thể hủy đơn hàng khi đang ở trạng thái: " + currentStatus
            );
        }

        // 3. Xác thực chuyển đổi sang trạng thái targetStatus qua State Machine
        orderStatusStateMachine.validateTransition(currentStatus, targetStatus, userRoles);

        // 4. Cập nhật trạng thái đơn hàng sang targetStatus
        order.setStatus(targetStatus);
        Order savedOrder = orderRepository.save(order);
        if (targetStatus == OrderStatus.CANCELLED) { enqueueRelease(order); }

        // 5. Định dạng lý do hủy kèm vai trò người thực hiện
        String primaryActor = resolvePrimaryActor(userRoles);
        String defaultReason = "Người dùng yêu cầu hủy đơn";
        String rawReason = (request != null && request.reason() != null && !request.reason().isBlank())
                ? request.reason().trim()
                : defaultReason;
        String formattedReason = formatAuditReason(primaryActor, rawReason, targetStatus);

        // 6. Ghi vết kiểm toán lịch sử trạng thái
        OrderStatusHistory historyRecord = OrderStatusHistory.builder()
                .order(savedOrder)
                .fromStatus(currentStatus)
                .toStatus(targetStatus)
                .reason(formattedReason)
                .changedAt(Instant.now())
                .build();
        orderStatusHistoryRepository.save(historyRecord);
        savedOrder.addStatusHistory(historyRecord);

        log.info("Hủy thành công đơn hàng {}: {} -> {} bởi actor=[{}]",
                orderId, currentStatus, targetStatus, primaryActor);

        return orderMapper.toDetailResponse(savedOrder);
    }

    // =========================================================================
    // Private Helper Methods
    // =========================================================================

    /**
     * Tra cứu thực thể đơn hàng theo ID hoặc ném ngoại lệ ORDER_NOT_FOUND (HTTP 404).
     */
    /** Không hủy đơn khi URL VNPAY còn có thể thu tiền; cần provider cancel/refund để mở nghiệp vụ này an toàn. */
    private void rejectActiveVnpayCancellation(Order order, OrderStatus target) {
        if (target == OrderStatus.CANCELLED && order.getStatus() == OrderStatus.PENDING_PAYMENT
                && "VNPAY".equals(order.getPaymentMethod())) {
            throw new BusinessException(ErrorCode.ORDER_CANNOT_CANCEL,
                    "Giao dịch VNPAY đang chờ kết quả; hãy kiểm tra thanh toán trước khi yêu cầu hủy");
        }
    }

    /** Cùng commit với hủy đơn; worker dùng service account hoàn kho, retry không giải phóng hai lần. */
    private void enqueueRelease(Order order) {
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,'RELEASE') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), order.getId(), order.getId());
    }

    /** Chưa có provider refund nên từ chối thay vì ghi trạng thái REFUNDED giả. */
    private BusinessException refundUnavailable() {
        return new BusinessException(ErrorCode.ORDER_CANNOT_CANCEL, "Chưa hỗ trợ hoàn tiền tự động; đơn đã thanh toán cần đối soát trước khi hoàn tiền");
    }

    /** Khóa cùng bản ghi với IPN đến hết transaction; kiểm tra trạng thái sau khi chờ. */
    private Order findOrderForUpdate(UUID orderId) {
        return orderRepository.findForPayment(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }

    /** Đọc đơn phục vụ truy vấn, không khóa ghi hoặc thay đổi trạng thái. */
    private Order findOrderById(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.ORDER_NOT_FOUND,
                        "Không tìm thấy đơn hàng với mã định danh: " + orderId
                ));
    }

    /**
     * Kiểm tra tính hợp lệ của mốc thời gian truy vấn (startDate <= endDate).
     */
    private void validateDateRange(Instant startDate, Instant endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessException(
                    ErrorCode.INVALID_PARAM_VALUE,
                    "Ngày bắt đầu (startDate) không được lớn hơn ngày kết thúc (endDate)"
            );
        }
    }

    /**
     * Kiểm tra xem tập hợp vai trò người dùng có chứa vai trò mục tiêu hay không.
     * Tự động chuẩn hóa loại bỏ tiền tố ROLE_ và không phân biệt chữ hoa thường.
     */
    private boolean hasRole(Collection<String> roles, String targetRole) {
        if (roles == null || roles.isEmpty() || targetRole == null) {
            return false;
        }
        String cleanTarget = cleanRolePrefix(targetRole);
        return roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(this::cleanRolePrefix)
                .anyMatch(r -> r.equalsIgnoreCase(cleanTarget));
    }

    /**
     * Xác định vai trò chính của tác tử để định dạng vào trường reason của audit log.
     * Ưu tiên thứ tự: ADMIN -> SELLER -> BUYER -> SYSTEM -> CARRIER/LOGISTICS -> USER.
     */
    private String resolvePrimaryActor(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "USER";
        }
        Set<String> normalized = roles.stream()
                .filter(r -> r != null && !r.isBlank())
                .map(this::cleanRolePrefix)
                .collect(Collectors.toSet());
        if (normalized.contains(OrderStatusStateMachine.ROLE_ADMIN)) {
            return OrderStatusStateMachine.ROLE_ADMIN;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_SELLER)) {
            return OrderStatusStateMachine.ROLE_SELLER;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_BUYER)) {
            return OrderStatusStateMachine.ROLE_BUYER;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_SYSTEM)) {
            return OrderStatusStateMachine.ROLE_SYSTEM;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_CARRIER)) {
            return OrderStatusStateMachine.ROLE_CARRIER;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_LOGISTICS)) {
            return OrderStatusStateMachine.ROLE_LOGISTICS;
        }
        if (normalized.contains(OrderStatusStateMachine.ROLE_PAYMENT)) {
            return OrderStatusStateMachine.ROLE_PAYMENT;
        }
        return normalized.stream().findFirst().orElse("USER");
    }

    /**
     * Định dạng chuỗi lý do kiểm toán theo mẫu "[ROLE] Lý do" và giới hạn an toàn 500 ký tự.
     */
    private String formatAuditReason(String actor, String reason, OrderStatus targetStatus) {
        String cleanReason = (reason != null && !reason.isBlank())
                ? reason.trim()
                : "Chuyển trạng thái sang " + targetStatus;
        String formatted = "[" + actor + "] " + cleanReason;
        if (formatted.length() > MAX_REASON_LENGTH) {
            return formatted.substring(0, MAX_REASON_LENGTH);
        }
        return formatted;
    }

    /**
     * Loại bỏ khoảng trắng và tiền tố ROLE_ để chuẩn hóa tên vai trò.
     */
    private String cleanRolePrefix(String role) {
        String trimmed = role.trim().toUpperCase(Locale.ROOT);
        if (trimmed.startsWith("ROLE_")) {
            return trimmed.substring(5);
        }
        return trimmed;
    }
}
