package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * DTO yêu cầu hủy đơn hàng từ phía Người mua (Buyer) hoặc Quản trị viên/Người bán (Admin/Seller).
 *
 * <p>Chức năng:
 * Tiếp nhận lý do hủy đơn hàng cho API POST /api/v1/orders/{orderId}/cancel.
 *
 * <p>Đầu vào và kết quả:
 * Nhận payload JSON từ HTTP client, được xác thực qua Jakarta Validation trước khi gửi tới OrderService.
 *
 * <p>Luồng xử lý:
 * Controller tiếp nhận -> OrderService kiểm tra quyền sở hữu của Buyer hoặc vai trò Admin ->
 * xác thực bước chuyển PENDING_PAYMENT / PAID -> CANCELLED qua OrderStatusStateMachine ->
 * ghi nhận lý do vào bảng order_status_history -> chuyển trạng thái đơn hàng sang CANCELLED.
 *
 * <p>Quy tắc và lý do:
 * Lý do hủy (reason) là bắt buộc (@NotBlank) để phục vụ công tác đối soát vận hành, giải phóng kho và hoàn tiền,
 * đồng thời giới hạn tối đa 500 ký tự khớp định dạng database.
 *
 * @param reason Lý do hủy đơn hàng (bắt buộc, không được để trống và tối đa 500 ký tự).
 */
@Builder
public record CancelOrderRequest(
        @NotBlank(message = "Lý do hủy đơn hàng không được để trống")
        @Size(max = 500, message = "Lý do hủy đơn hàng không được vượt quá 500 ký tự")
        String reason
) {
}
