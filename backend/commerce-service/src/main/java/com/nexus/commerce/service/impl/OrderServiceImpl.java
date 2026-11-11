package com.nexus.commerce.service.impl;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.UserContactClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.entity.OrderStatusHistory;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.NotificationOutbox;
import com.nexus.commerce.service.OrderService;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final int MAX_REASON_LENGTH = 500;

    // Order.items đã quản lý snapshot bằng JPA cascade; service chỉ cần repository của đơn và lịch sử.
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final OrderStatusStateMachine orderStatusStateMachine;
    private final OrderMapper orderMapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final CatalogClient catalogClient;
    private final NotificationOutbox notificationOutbox;
    private final UserContactClient userContactClient;

    public OrderServiceImpl(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OrderStatusStateMachine orderStatusStateMachine,
            OrderMapper orderMapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this(orderRepository, orderStatusHistoryRepository, orderStatusStateMachine, orderMapper, jdbc, null, null, null);
    }

    public OrderServiceImpl(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OrderStatusStateMachine orderStatusStateMachine,
            OrderMapper orderMapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            CatalogClient catalogClient) {
        this(orderRepository, orderStatusHistoryRepository, orderStatusStateMachine, orderMapper,
                jdbc, catalogClient, null, null);
    }

    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            OrderStatusStateMachine orderStatusStateMachine,
            OrderMapper orderMapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc,
            @Autowired(required = false) CatalogClient catalogClient,
            @Autowired(required = false) NotificationOutbox notificationOutbox,
            @Autowired(required = false) UserContactClient userContactClient) {
        this.orderRepository = orderRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.orderStatusStateMachine = orderStatusStateMachine;
        this.orderMapper = orderMapper;
        this.jdbc = jdbc;
        this.catalogClient = catalogClient;
        this.notificationOutbox = notificationOutbox;
        this.userContactClient = userContactClient;
    }

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
        // Thu COD đã ghi nhận thì lặp command chỉ đọc kết quả, kể cả đang chờ kho hoặc cần đối soát.
        // Vẫn kiểm tra quyền xác nhận tiền trước replay; không cho seller tự xác nhận thu COD.
        if (targetStatus == OrderStatus.PAID && "COD".equals(order.getPaymentMethod()) && order.getFulfillmentStatus() != null) {
            orderStatusStateMachine.validateTransition(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, userRoles);
            return orderMapper.toDetailResponse(order);
        }
        // Đơn có lỗi/chưa xác nhận kho không được đi tiếp quy trình giao hàng, kể cả dữ liệu cũ đã PROCESSING.
        if (order.getFulfillmentStatus() != null && !"CONFIRMED".equals(order.getFulfillmentStatus())
                && Set.of(OrderStatus.PROCESSING, OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED).contains(targetStatus)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Đơn chưa được kho xác nhận hoặc cần đối soát");
        }
        // 2. Kiểm tra tính Idempotent (chống trùng lặp yêu cầu mạng)
        if (currentStatus == targetStatus) {
            log.info("Đơn hàng {} hiện đã ở trạng thái {}, xử lý idempotent trả về kết quả hiện tại",
                    orderId, targetStatus);
            return orderMapper.toDetailResponse(order);
        }

        // Chưa có provider refund: không cho API đánh dấu đã hoàn tiền khi chưa hoàn tiền thật.
        if (targetStatus == OrderStatus.REFUNDED) { throw refundUnavailable(); }
        // VNPAY/đơn winner chỉ được PAID qua IPN và worker kho, kể cả trước khi marker AUCTION chuyển sang provider VNPAY.
        if (targetStatus == OrderStatus.PAID && ("VNPAY".equals(order.getPaymentMethod())
                || order.getSourceType() == OrderSourceType.AUCTION)) {
            throw new BusinessException(ErrorCode.ORDER_STATUS_INVALID, "Thanh toán VNPAY chỉ xác nhận qua IPN");
        }

        // 3. Xác thực tính hợp lệ của chuyển đổi qua máy trạng thái
        orderStatusStateMachine.validateTransition(currentStatus, targetStatus, userRoles);

        // 4. Cập nhật thực thể đơn hàng
        // COD chỉ chốt giá khi thao tác backend hợp lệ xác nhận PAID; giá lấy từ dự kiến đã lưu, không từ request.
        if (targetStatus == OrderStatus.PAID && "COD".equals(order.getPaymentMethod()) && !order.isPriceFinalized()) {
            return confirmCodFunds(order, currentUserId, userRoles);
        }
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
    /** COD đã thu tiền/chờ kho và VNPAY có thể thu tiền không được hủy như đơn chưa trả; chưa có refund provider. */
    private void rejectActiveVnpayCancellation(Order order, OrderStatus target) {
        if (target == OrderStatus.CANCELLED && order.isPriceFinalized() && order.getFulfillmentStatus() != null) {
            throw refundUnavailable();
        }
        if (target == OrderStatus.CANCELLED && order.getStatus() == OrderStatus.PENDING_PAYMENT
                && "VNPAY".equals(order.getPaymentMethod())) {
            // Draft chưa có attempt hoặc chỉ FAILED được hủy; không hủy khi cổng còn có thể thu tiền/chờ kho/đối soát.
            Integer active = jdbc.queryForObject("SELECT count(*) FROM payments WHERE order_id=? "
                    + "AND (status IN ('PENDING','SUCCESS','REVIEW') OR inventory_status='REVIEW')", Integer.class, order.getId());
            if (active != null && active > 0) {
                throw new BusinessException(ErrorCode.ORDER_CANNOT_CANCEL,
                        "Giao dịch VNPAY đang chờ kết quả; hãy kiểm tra thanh toán trước khi yêu cầu hủy");
            }
        }
    }

    /**
     * Ghi xác nhận thu COD và giá chính thức cùng COD_COMMIT trong transaction, chưa đổi Order sang PAID.
     * Worker xác nhận toàn bộ phiếu kho rồi mới PAID; lỗi kho giữ giá/tiền và chuyển REVIEW, không thu/hoàn tiền giả.
     * Khóa Order của caller và fulfillmentStatus chặn lặp command, history chỉ ghi một lần nhận tiền.
     */
    private OrderDetailResponse confirmCodFunds(Order order, UUID actor, List<String> roles) {
        com.nexus.commerce.service.PaymentPricingService.finalizePrices(order,
                com.nexus.commerce.service.PaymentPricingService.estimated(order));
        order.setFulfillmentStatus("PENDING");
        jdbc.update("INSERT INTO payment_inventory_tasks(id,order_id,target_id,action) VALUES (?,?,?,'COD_COMMIT') ON CONFLICT DO NOTHING",
                UUID.randomUUID(), order.getId(), order.getId());
        var history = OrderStatusHistory.builder().order(order).fromStatus(order.getStatus()).toStatus(order.getStatus())
                .reason("[" + resolvePrimaryActor(roles) + "] COD_FUNDS_RECEIVED_PENDING_INVENTORY")
                .changedAt(Instant.now()).build();
        orderStatusHistoryRepository.save(history);
        order.addStatusHistory(history);
        orderRepository.save(order);
        log.info("Đã ghi nhận thu COD, chờ xác nhận kho: order={}, actor={}", order.getId(), actor);
        return orderMapper.toDetailResponse(order);
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

    /**
     * Chức năng: Đọc đơn hàng cùng danh sách sản phẩm snapshot (items) phục vụ truy vấn chi tiết.
     * Đầu vào và kết quả: Nhận UUID orderId, trả về entity Order có sẵn items.
     * Luồng xử lý: Gọi orderRepository.findWithItemsById(orderId) để nạp trước items.
     * Quy tắc và lý do: Không khóa bản ghi; tránh LazyInitializationException khi session JPA đã đóng.
     * Lỗi và giới hạn: Ném BusinessException ORDER_NOT_FOUND nếu không tìm thấy đơn.
     */
    private Order findOrderById(UUID orderId) {
        return orderRepository.findWithItemsById(orderId)
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
     * Khởi tạo đơn hàng từ kết quả quyết toán phiên đấu giá thành công (SRS §3.5.3 & §3.4.3).
     *
     * <p>Chức năng:
     * - Tiếp nhận thông tin kết quả chốt phiên đấu giá (Settlement Data) được bàn giao từ Auction Service.
     * - Lưu trữ ảnh chụp nhanh (Snapshot) thông tin sản phẩm và giá trúng đấu giá tại thời điểm chốt phiên.
     * - Tạo đơn hàng sourceType = AUCTION, status = PENDING_PAYMENT, hạn thanh toán theo quy định (mặc định 24h).
     * - Đảm bảo tính lũy nghiệm (Idempotency) tuyệt đối theo auctionId (chỉ tạo duy nhất 1 order cho 1 phiên thắng).
     *
     * <p>Đầu vào và kết quả:
     * - Đầu vào: CreateAuctionOrderRequest gồm auctionId, winnerId, sellerId, productId, skuId, finalPrice, paymentDeadline.
     * - Kết quả: AuctionOrderResponse chứa orderId, orderNo, auctionId, status, total, paymentDeadline.
     *
     * <p>Luồng xử lý:
     * 1. Kiểm tra tính hợp lệ cơ bản của dữ liệu đầu vào (không null, finalPrice > 0, winner != seller).
     * 2. Kiểm tra Idempotency: Tra cứu Order theo auctionId trong DB. Nếu đã tồn tại, trả về đơn hàng hiện hữu ngay lập tức.
     * 3. Thu thập snapshot sản phẩm và SKU: ưu tiên dữ liệu từ request hoặc truy vấn Catalog qua CatalogClient.
     * 4. Sinh mã đơn hàng duy nhất theo định dạng "AUC-yyyyMMdd-XXXXXX".
     * 5. Khởi tạo thực thể Order với sourceType = AUCTION, paymentMethod = "AUCTION", status = PENDING_PAYMENT.
     * 6. Tạo dòng mặt hàng OrderItem tương ứng với số lượng = 1 và đơn giá = finalPrice.
     * 7. Ghi nhận bước trạng thái khởi tạo vào OrderStatusHistory.
     * 8. Lưu Order và OrderItem vào DB trong một transaction duy nhất.
     *
     * <p>Quy tắc và lý do:
     * - Tuân thủ SRS §3.5.3 và nguyên tắc phân định trách nhiệm: Auction Service không tự tạo đơn trong CSDL của mình.
     * - auctionId được dùng làm Idempotency Key tự nhiên để ngăn chặn trùng lặp khi network retry.
     *
     * <p>Lỗi và giới hạn:
     * - Bắn BusinessException(ErrorCode.INVALID_PARAM_VALUE) nếu tham số bị null hoặc giá <= 0.
     * - Bắn BusinessException(ErrorCode.CHECKOUT_BUYER_IS_SELLER) nếu người thắng là người bán.
     */
    @Override
    @Transactional
    public AuctionOrderResponse createAuctionOrder(CreateAuctionOrderRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Yêu cầu tạo đơn hàng đấu giá không được để trống");
        }
        if (request.auctionId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }
        if (request.winnerId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người thắng cuộc không được để trống");
        }
        if (request.sellerId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người bán không được để trống");
        }
        if (request.skuId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã SKU không được để trống");
        }
        if (request.finalPrice() == null || request.finalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Giá trúng đấu giá phải lớn hơn 0");
        }
        if (request.winnerId().equals(request.sellerId())) {
            throw new BusinessException(ErrorCode.ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT, "Người thắng cuộc không được trùng với người bán");
        }

        // Bước 1: Kiểm tra tính lũy nghiệm (Idempotency - SRS §3.4.3 & §2.6 ORDER_IDEMPOTENCY_ENABLED = true)
        // Khóa transaction theo auctionId trước đọc/insert; hai handover cùng phiên phải replay đơn đã commit, không lỗi unique.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "auction-order:" + request.auctionId());
        Optional<Order> existing = orderRepository.findByAuctionId(request.auctionId());
        if (existing.isPresent()) {
            Order order = existing.get();
            log.info("Đơn hàng cho phiên đấu giá [{}] đã tồn tại với orderId [{}] (Idempotent), trả về đơn hàng hiện tại",
                    request.auctionId(), order.getId());

            // Nếu request có bổ sung reservationIds mà đơn cũ chưa có, cập nhật thêm vào đơn
            if (request.reservationIds() != null && !request.reservationIds().isEmpty()) {
                boolean updated = false;
                for (UUID resId : request.reservationIds()) {
                    if (!order.getReservationIds().contains(resId)) {
                        order.getReservationIds().add(resId);
                        updated = true;
                    }
                }
                if (updated) {
                    orderRepository.save(order);
                }
            }

            Instant deadline = (order.getPaymentDeadline() != null)
                    ? order.getPaymentDeadline()
                    : ((request.paymentDeadline() != null)
                            ? request.paymentDeadline()
                            : order.getCreatedAt().plus(24, ChronoUnit.HOURS));
            return new AuctionOrderResponse(
                    order.getId(),
                    order.getOrderNo(),
                    order.getAuctionId(),
                    order.getBuyerId(),
                    order.getSellerId(),
                    order.getStatus().name(),
                    order.isPriceFinalized() ? order.getTotal() : order.getEstimatedTotal(),
                    deadline,
                    order.getCreatedAt()
            );
        }

        // Bước 2: Chuẩn bị snapshot thông tin sản phẩm và SKU (Snapshot Capture)
        String productName = request.productName();
        String skuCode = request.skuCode();
        if ((productName == null || productName.isBlank()) && catalogClient != null) {
            try {
                SkuInfo skuInfo = catalogClient.getSkuInfo(request.skuId());
                if (skuInfo != null) {
                    productName = skuInfo.productName();
                    skuCode = skuInfo.skuCode();
                }
            } catch (Exception ex) {
                log.warn("Không thể tải thông tin SKU từ Catalog cho skuId [{}], dùng giá trị fallback: {}",
                        request.skuId(), ex.getMessage());
            }
        }
        if (productName == null || productName.isBlank()) {
            productName = (request.productId() != null)
                    ? "Sản phẩm đấu giá #" + request.productId()
                    : "Sản phẩm đấu giá #" + request.auctionId();
        }
        if (skuCode == null || skuCode.isBlank()) {
            skuCode = "SKU-AUC-" + request.skuId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        }

        Instant now = Instant.now();
        Instant deadline = (request.paymentDeadline() != null)
                ? request.paymentDeadline()
                : now.plus(24, ChronoUnit.HOURS);

        // Bước 3: Sinh mã đơn hàng đấu giá duy nhất
        String orderNo = generateAuctionOrderNo(now);

        // Bước 4: Khởi tạo Order entity với sourceType = AUCTION, status = PENDING_PAYMENT và paymentDeadline
        String winnerEmail = (request.winnerEmail() != null && !request.winnerEmail().isBlank())
                ? request.winnerEmail().trim()
                : null;
        String winnerName = (request.winnerName() != null && !request.winnerName().isBlank())
                ? request.winnerName().trim()
                : null;
        if ((winnerEmail == null || winnerName == null) && userContactClient != null) {
            var contactOpt = userContactClient.findContact(request.winnerId());
            if (contactOpt.isPresent()) {
                var contact = contactOpt.get();
                if (winnerEmail == null && contact.email() != null && !contact.email().isBlank()) {
                    winnerEmail = contact.email().trim();
                }
                if (winnerName == null && contact.fullName() != null && !contact.fullName().isBlank()) {
                    winnerName = contact.fullName().trim();
                }
            }
        }

        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo(orderNo)
                .buyerId(request.winnerId())
                .sellerId(request.sellerId())
                .buyerEmail(winnerEmail)
                .recipientName(winnerName)
                .sourceType(OrderSourceType.AUCTION)
                .auctionId(request.auctionId())
                // Giá thắng được giữ ở estimated để payment không áp lại giá bán thường; chỉ chốt sau thu tiền.
                .estimatedSubtotal(request.finalPrice())
                .estimatedShippingFee(BigDecimal.ZERO)
                .estimatedDiscount(BigDecimal.ZERO)
                .estimatedTotal(request.finalPrice())
                .subtotal(null)
                .shippingFee(null)
                .discount(null)
                .total(null)
                .priceFinalized(false)
                .status(OrderStatus.PENDING_PAYMENT)
                .shippingAddress("AUCTION_PENDING_BUYER_ADDRESS")
                .idempotencyKey(request.auctionId().toString())
                .paymentMethod("AUCTION")
                .paymentDeadline(deadline)
                .createdAt(now)
                .build();

        // Gắn reservationIds nếu được truyền kèm ngay khi tạo
        if (request.reservationIds() != null && !request.reservationIds().isEmpty()) {
            order.getReservationIds().addAll(request.reservationIds());
        }

        // Bước 5: Tạo snapshot mặt hàng OrderItem (số lượng = 1 đơn vị theo quy chuẩn đấu giá)
        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID())
                .skuId(request.skuId())
                .productName(productName)
                .skuCode(skuCode)
                .estimatedUnitPrice(request.finalPrice())
                .unitPrice(null)
                .quantity(1)
                .build();
        order.addItem(item);

        // Bước 6: Ghi nhận lịch sử trạng thái ban đầu PENDING_PAYMENT
        OrderStatusHistory history = OrderStatusHistory.builder()
                .fromStatus(null)
                .toStatus(OrderStatus.PENDING_PAYMENT)
                .changedAt(now)
                .reason(formatAuditReason("AUCTION_SERVICE", "Tạo đơn hàng từ kết quả phiên đấu giá thành công " + request.auctionId(), OrderStatus.PENDING_PAYMENT))
                .build();
        order.addStatusHistory(history);

        // Bước 7: Lưu đơn hàng vào cơ sở dữ liệu và ghi snapshot thông báo chúc mừng thắng đấu giá vào outbox
        Order savedOrder = orderRepository.save(order);
        if (notificationOutbox != null) {
            notificationOutbox.enqueue(savedOrder, "AUCTION_WON");
        }
        log.info("Khởi tạo thành công đơn hàng đấu giá [{}] cho phiên [{}] với winner [{}], tổng tiền [{}]",
                savedOrder.getOrderNo(), request.auctionId(), request.winnerId(), savedOrder.getEstimatedTotal());

        return new AuctionOrderResponse(
                savedOrder.getId(),
                savedOrder.getOrderNo(),
                savedOrder.getAuctionId(),
                savedOrder.getBuyerId(),
                savedOrder.getSellerId(),
                savedOrder.getStatus().name(),
                savedOrder.isPriceFinalized() ? savedOrder.getTotal() : savedOrder.getEstimatedTotal(),
                deadline,
                savedOrder.getCreatedAt()
        );
    }

    /**
     * Gắn danh sách mã phiếu giữ kho (reservation IDs) vào đơn hàng.
     *
     * Chức năng:
     * - Lưu các reservationId đã chuyển tiếp từ Fulfillment Service vào bảng order_reservations.
     * - Cung cấp dữ liệu cho PaymentService kiểm tra điều kiện phiếu kho trước khi tạo URL VNPAY,
     *   và phục vụ PaymentInventoryWorker xuất kho sau khi đơn thanh toán thành công.
     *
     * Đầu vào và kết quả:
     * - Đầu vào: orderId (mã đơn hàng), reservationIds (danh sách ID phiếu kho).
     * - Kết quả: Cập nhật trường reservationIds của Order trong CSDL.
     *
     * Quy tắc và lý do:
     * - Thao tác là idempotent: kiểm tra sự tồn tại của từng reservationId trước khi thêm, tránh bản ghi trùng.
     * - Nếu đơn hàng không tồn tại, ném ResourceNotFoundException.
     *
     * Lỗi và giới hạn:
     * - Bắn BusinessException(ErrorCode.INVALID_PARAM_VALUE) nếu tham số bị null hoặc rỗng.
     */
    @Override
    @Transactional
    public void attachReservations(UUID orderId, List<UUID> reservationIds) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã đơn hàng orderId không được để trống");
        }
        if (reservationIds == null || reservationIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Danh sách reservationIds không được để trống");
        }

        // Đồng bộ attach với start/IPN dưới cùng khóa Order, tránh hai lần replay insert trùng order_reservations.
        Order order = orderRepository.findForPayment(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND, "Không tìm thấy đơn hàng với mã: " + orderId));

        boolean modified = false;
        for (UUID resId : reservationIds) {
            if (resId != null && !order.getReservationIds().contains(resId)) {
                order.getReservationIds().add(resId);
                modified = true;
            }
        }

        if (modified) {
            orderRepository.save(order);
            log.info("Đã gắn [{}] phiếu giữ kho vào đơn hàng [{}]", reservationIds.size(), orderId);
        } else {
            log.info("Các phiếu giữ kho đã tồn tại trong đơn hàng [{}] (Idempotent), bỏ qua cập nhật lặp", orderId);
        }
    }

    /**
     * Sinh mã đơn hàng đấu giá thân thiện theo định dạng AUC-yyyyMMdd-XXXXXX.
     */
    private String generateAuctionOrderNo(Instant now) {
        String datePart = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(java.time.ZoneOffset.UTC)
                .format(now);
        String randomPart = UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        return "AUC-" + datePart + "-" + randomPart;
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
