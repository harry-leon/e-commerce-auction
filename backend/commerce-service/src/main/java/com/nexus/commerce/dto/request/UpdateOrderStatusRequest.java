package com.nexus.commerce.dto.request;

import com.nexus.commerce.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

/**
 * DTO yêu cầu cập nhật trạng thái đơn hàng từ Người bán (Seller) hoặc Quản trị viên (Admin).
 *
 * <p>Chức năng:
 * Tiếp nhận trạng thái mục tiêu và lý do cập nhật cho API PATCH /api/v1/orders/{orderId}/status.
 *
 * <p>Đầu vào và kết quả:
 * Nhận payload JSON từ HTTP client, được Spring MVC xác thực qua Bean Validation trước khi chuyển vào OrderService.
 *
 * <p>Luồng xử lý:
 * Controller tiếp nhận và validate -> OrderService kiểm tra quyền sở hữu và máy trạng thái OrderStatusStateMachine ->
 * ghi vết kiểm toán vào bảng order_status_history -> cập nhật trạng thái Order.
 *
 * <p>Quy tắc và lý do:
 * Trường status là bắt buộc (@NotNull). Trường reason có giới hạn tối đa 500 ký tự (@Size(max = 500))
 * để phù hợp với độ dài cột vật lý varchar(500) trong schema cơ sở dữ liệu V2__init_schema.sql.
 *
 * @param status Trạng thái đích muốn chuyển sang (bắt buộc NOT NULL).
 * @param reason Lý do hoặc ghi chú chuyển trạng thái (tùy chọn, tối đa 500 ký tự).
 */
@Builder
public record UpdateOrderStatusRequest(
        @NotNull(message = "Trạng thái đơn hàng không được để trống")
        OrderStatus status,

        @Size(max = 500, message = "Lý do cập nhật trạng thái không được vượt quá 500 ký tự")
        String reason
) {
}
