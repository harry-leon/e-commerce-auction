package com.nexus.commerce.service;

import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interface định nghĩa các nghiệp vụ cốt lõi quản lý vòng đời đơn hàng (Issue HG-S1-4 / SRS §3.4.3).
 *
 * <p>Chức năng:
 * <ul>
 *   <li>Tra cứu lịch sử mua hàng của Người mua và danh sách đơn của Người bán có phân trang và bộ lọc.</li>
 *   <li>Truy xuất chi tiết đơn hàng kèm kiểm tra quyền sở hữu (Buyer, Seller, Admin).</li>
 *   <li>Thực thi chuyển đổi trạng thái đơn hàng với xác thực máy trạng thái và ghi audit log.</li>
 *   <li>Hủy đơn hàng an toàn bởi Người mua hoặc Quản trị viên.</li>
 * </ul>
 *
 * <p>Quy tắc nghiệp vụ:
 * Đảm bảo tính cô lập dữ liệu người dùng (Tenant Isolation), tuân thủ ma trận chuyển trạng thái
 * OrderStatusStateMachine và tính lũy kế (idempotency) khi có yêu cầu trùng lặp mạng.
 */
public interface OrderService {

    /**
     * Lấy danh sách lịch sử đơn hàng của người mua (có phân trang và lọc theo trạng thái/thời gian Instant).
     *
     * @param buyerId   ID người mua (Keycloak User ID)
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn, null nếu lấy tất cả)
     * @param startDate Mốc thời gian bắt đầu UTC (tùy chọn)
     * @param endDate   Mốc thời gian kết thúc UTC (tùy chọn)
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    Page<OrderSummaryResponse> getBuyerOrders(
            UUID buyerId,
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    );

    /**
     * Tiện ích nạp chồng hỗ trợ Controller truyền LocalDateTime thay vì Instant.
     * Tự động chuyển đổi LocalDateTime sang Instant theo múi giờ UTC chuẩn hệ thống.
     *
     * @param buyerId   ID người mua
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn)
     * @param startDate Mốc thời gian bắt đầu LocalDateTime
     * @param endDate   Mốc thời gian kết thúc LocalDateTime
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    // Default overload gọi nội bộ bản Instant, không đi qua proxy lần nữa; cần mở transaction ngay tại đây để mapper đọc items lazy.
    @Transactional(readOnly = true)
    default Page<OrderSummaryResponse> getBuyerOrders(
            UUID buyerId,
            OrderStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        Instant start = (startDate != null) ? startDate.toInstant(ZoneOffset.UTC) : null;
        Instant end = (endDate != null) ? endDate.toInstant(ZoneOffset.UTC) : null;
        return getBuyerOrders(buyerId, status, start, end, pageable);
    }

    /**
     * Xem thông tin chi tiết một đơn hàng, bao gồm các mặt hàng snapshot và dòng thời gian lịch sử trạng thái.
     * Kiểm tra chặt chẽ quyền sở hữu (Buyer là chủ đơn, Seller là chủ gian hàng, hoặc Admin hệ thống).
     *
     * @param orderId       ID đơn hàng cần xem
     * @param currentUserId ID người dùng đang đăng nhập từ JWT Token
     * @param userRoles     Danh sách các vai trò Keycloak của người dùng (ví dụ: ROLE_BUYER, ROLE_ADMIN)
     * @return Thông tin chi tiết đơn hàng OrderDetailResponse
     */
    OrderDetailResponse getOrderDetail(
            UUID orderId,
            UUID currentUserId,
            List<String> userRoles
    );

    /**
     * Tiện ích nạp chồng hỗ trợ truy vấn chi tiết đơn hàng nhận cờ isAdmin kiểu boolean.
     *
     * @param orderId       ID đơn hàng cần xem
     * @param currentUserId ID người dùng đang đăng nhập
     * @param isAdmin       true nếu là Quản trị viên
     * @return Thông tin chi tiết đơn hàng OrderDetailResponse
     */
    // Giữ transaction cho cả overload boolean để items/history được đọc trước khi đóng Hibernate session.
    @Transactional(readOnly = true)
    default OrderDetailResponse getOrderDetail(
            UUID orderId,
            UUID currentUserId,
            boolean isAdmin
    ) {
        List<String> roles = isAdmin
                ? List.of(OrderStatusStateMachine.ROLE_ADMIN)
                : Collections.emptyList();
        return getOrderDetail(orderId, currentUserId, roles);
    }

    /**
     * Lấy danh sách đơn hàng thuộc phạm vi quản lý của người bán (có phân trang và bộ lọc).
     *
     * @param sellerId  ID người bán / chủ gian hàng
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn)
     * @param startDate Mốc thời gian bắt đầu UTC (tùy chọn)
     * @param endDate   Mốc thời gian kết thúc UTC (tùy chọn)
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    Page<OrderSummaryResponse> getSellerOrders(
            UUID sellerId,
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    );

    /**
     * Tiện ích nạp chồng hỗ trợ Người bán lọc theo LocalDateTime từ Controller.
     *
     * @param sellerId  ID người bán
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn)
     * @param startDate Mốc thời gian bắt đầu LocalDateTime
     * @param endDate   Mốc thời gian kết thúc LocalDateTime
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    // Cùng biên giao dịch với buyer: chuyển ngày UTC rồi truy vấn/ánh xạ trong một transaction read-only.
    @Transactional(readOnly = true)
    default Page<OrderSummaryResponse> getSellerOrders(
            UUID sellerId,
            OrderStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        Instant start = (startDate != null) ? startDate.toInstant(ZoneOffset.UTC) : null;
        Instant end = (endDate != null) ? endDate.toInstant(ZoneOffset.UTC) : null;
        return getSellerOrders(sellerId, status, start, end, pageable);
    }

    /**
     * Lấy danh sách toàn bộ đơn hàng trong hệ thống (dành cho Quản trị viên, có phân trang và bộ lọc).
     *
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn)
     * @param startDate Mốc thời gian bắt đầu UTC (tùy chọn)
     * @param endDate   Mốc thời gian kết thúc UTC (tùy chọn)
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    Page<OrderSummaryResponse> getAdminOrders(
            OrderStatus status,
            Instant startDate,
            Instant endDate,
            Pageable pageable
    );

    /**
     * Tiện ích nạp chồng hỗ trợ Quản trị viên lọc theo LocalDateTime từ Controller.
     *
     * @param status    Trạng thái đơn hàng cần lọc (tùy chọn)
     * @param startDate Mốc thời gian bắt đầu LocalDateTime
     * @param endDate   Mốc thời gian kết thúc LocalDateTime
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách tóm tắt đơn hàng Page
     */
    // Overload mặc định cũng phải có transaction; annotation trên implementation không bao phủ lời gọi nội bộ này.
    @Transactional(readOnly = true)
    default Page<OrderSummaryResponse> getAdminOrders(
            OrderStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate,
            Pageable pageable
    ) {
        Instant start = (startDate != null) ? startDate.toInstant(ZoneOffset.UTC) : null;
        Instant end = (endDate != null) ? endDate.toInstant(ZoneOffset.UTC) : null;
        return getAdminOrders(status, start, end, pageable);
    }

    /**
     * Cập nhật trạng thái đơn hàng (dành cho Người bán, Quản trị viên hoặc hệ thống vận chuyển/thanh toán).
     *
     * <p>Đặc tính kỹ thuật:
     * <ul>
     *   <li>Đảm bảo tính Idempotent: Nếu đơn hàng đã ở trạng thái đích, trả về dữ liệu hiện tại mà không báo lỗi.</li>
     *   <li>Xác thực bước chuyển hợp lệ qua OrderStatusStateMachine theo phân quyền vai trò.</li>
     *   <li>Ghi vết kiểm toán bất biến vào bảng order_status_history với lý do định dạng "[ROLE] reason".</li>
     * </ul>
     *
     * @param orderId       ID đơn hàng cần cập nhật
     * @param request       Dữ liệu yêu cầu chuyển trạng thái và lý do
     * @param currentUserId ID người thực hiện
     * @param userRoles     Danh sách vai trò của người thực hiện
     * @return Thông tin chi tiết đơn hàng sau khi cập nhật OrderDetailResponse
     */
    OrderDetailResponse updateOrderStatus(
            UUID orderId,
            UpdateOrderStatusRequest request,
            UUID currentUserId,
            List<String> userRoles
    );

    /**
     * Hủy đơn hàng theo yêu cầu của Người mua hoặc can thiệp của Quản trị viên.
     *
     * <p>Quy tắc nghiệp vụ:
     * <ul>
     *   <li>Chỉ Người mua sở hữu đơn hoặc Quản trị viên (ADMIN) mới có quyền gửi yêu cầu hủy.</li>
     *   <li>Chỉ hủy đơn PENDING_PAYMENT không có giao dịch VNPAY đang chờ; PAID cần refund provider chưa triển khai.</li>
     *   <li>Xử lý idempotent an toàn nếu đơn hàng đã ở trạng thái CANCELLED.</li>
     *   <li>Ghi vết lý do hủy và chuyển trạng thái đơn sang CANCELLED trong cùng một transaction.</li>
     * </ul>
     *
     * @param orderId       ID đơn hàng cần hủy
     * @param request       Yêu cầu hủy kèm lý do bắt buộc
     * @param currentUserId ID người thực hiện
     * @param userRoles     Danh sách vai trò của người thực hiện
     * @return Thông tin chi tiết đơn hàng sau khi hủy OrderDetailResponse
     */
    OrderDetailResponse cancelOrder(
            UUID orderId,
            CancelOrderRequest request,
            UUID currentUserId,
            List<String> userRoles
    );

    /**
     * Khởi tạo đơn hàng từ kết quả quyết toán phiên đấu giá thành công (SRS §3.5.3 & §3.4.3).
     *
     * <p>Chức năng:
     * <ul>
     *   <li>Nhận dữ liệu bàn giao từ Auction Service (winnerId, sellerId, finalPrice, skuId, auctionId).</li>
     *   <li>Lưu trữ snapshot thông tin sản phẩm và giá trúng đấu giá tại thời điểm chốt phiên.</li>
     *   <li>Tạo đơn hàng sourceType = AUCTION, status = PENDING_PAYMENT, hạn thanh toán 24h.</li>
     *   <li>Đảm bảo tính lũy nghiệm (Idempotency) tuyệt đối theo auctionId: chỉ tạo đúng 1 order cho 1 phiên thắng.</li>
     * </ul>
     *
     * @param request dữ liệu yêu cầu tạo đơn từ Auction Service
     * @return kết quả thông tin đơn hàng AuctionOrderResponse
     */
    AuctionOrderResponse createAuctionOrder(CreateAuctionOrderRequest request);

    /**
     * Gắn danh sách mã phiếu giữ kho (reservation IDs) vào đơn hàng.
     *
     * <p>Chức năng:
     * - Lưu các reservationId đã chuyển tiếp từ Fulfillment Service vào bảng liên kết order_reservations.
     * - Cung cấp dữ liệu cho PaymentService kiểm tra trước khi mở URL VNPAY và cho PaymentInventoryWorker xuất kho.
     *
     * @param orderId        Mã đơn hàng Commerce cần gắn phiếu kho
     * @param reservationIds Danh sách mã phiếu giữ kho từ Fulfillment Service
     */
    void attachReservations(UUID orderId, List<UUID> reservationIds);
}
