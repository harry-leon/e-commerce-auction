package com.nexus.commerce.service;

import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.PageResponse;
import com.nexus.commerce.enums.OrderStatus;
import java.util.UUID;
import org.springframework.data.domain.Pageable;

/**
 * Service interface định nghĩa các nghiệp vụ cốt lõi quản lý Đơn hàng (Order Foundation).
 *
 * <p>Quy chuẩn theo SRS §3.4.3, BR-COM-01, BR-COM-02 và API_SPEC_CART_ORDER.md.
 */
public interface OrderService {

    /**
     * Tạo và lưu trữ đơn hàng mới phát sinh từ quy trình Checkout.
     *
     * <p>Xử lý chống trùng lặp qua Idempotency-Key, snapshot giá/sản phẩm từ Catalog,
     * gọi giữ chỗ tồn kho (Reservation) 15 phút, ghi nhận lịch sử trạng thái ban đầu,
     * và dọn dẹp các mặt hàng đã mua khỏi giỏ hàng.
     *
     * @param buyerId        ID định danh người mua hàng
     * @param idempotencyKey Khóa chống trùng lặp gửi từ Header HTTP
     * @param request        Thông tin tạo đơn hàng từ client
     * @return OrderResponse thông tin chi tiết đơn hàng đã tạo
     */
    OrderResponse createOrder(UUID buyerId, String idempotencyKey, CreateOrderRequest request);

    /**
     * Tra cứu thông tin chi tiết một đơn hàng theo ID.
     *
     * @param orderId         ID của đơn hàng
     * @param requesterId     ID người dùng thực hiện yêu cầu
     * @param isAdminOrSeller Cờ xác định quyền Admin hoặc Seller
     * @return OrderResponse chi tiết đơn hàng
     */
    OrderResponse getOrderById(UUID orderId, UUID requesterId, boolean isAdminOrSeller);

    /**
     * Lấy danh sách lịch sử đơn hàng của người mua có phân trang.
     *
     * @param buyerId  ID của người mua
     * @param status   Trạng thái đơn hàng cần lọc (tuỳ chọn, null để lấy tất cả)
     * @param pageable Cấu hình phân trang và sắp xếp
     * @return PageResponse chứa danh sách OrderResponse
     */
    PageResponse<OrderResponse> getMyOrders(UUID buyerId, OrderStatus status, Pageable pageable);

    /**
     * Chuyển đổi trạng thái đơn hàng theo máy trạng thái hợp lệ (Order State Machine)
     * và tự động lưu vết kiểm toán vào order_status_history.
     *
     * @param orderId   ID đơn hàng cần cập nhật
     * @param newStatus Trạng thái mới muốn chuyển sang
     * @param changedBy ID người hoặc hệ thống thực hiện chuyển đổi
     * @param reason    Lý do hoặc ghi chú chuyển trạng thái
     * @return OrderResponse thông tin đơn hàng sau khi cập nhật
     */
    OrderResponse changeOrderStatus(UUID orderId, OrderStatus newStatus, UUID changedBy, String reason);
}
