package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu gắn danh sách phiếu giữ kho (reservation IDs) vào đơn hàng Commerce.
 *
 * Chức năng:
 * - Tiếp nhận danh sách reservationId đã được chuyển tiếp từ Fulfillment Service (AUCTION -> ORDER).
 * - Lưu liên kết vào bảng order_reservations để PaymentService kiểm tra điều kiện giữ kho trước khi thanh toán,
 *   đồng thời làm căn cứ để PaymentInventoryWorker xác nhận xuất kho (CONFIRM) sau khi thanh toán VNPAY thành công.
 *
 * Đầu vào và kết quả:
 * - Đầu vào: reservationIds - danh sách UUID của các phiếu giữ kho tại Fulfillment Service.
 * - Kết quả: Đơn hàng được cập nhật danh sách reservationIds trong CSDL.
 *
 * Quy tắc và lý do:
 * - Danh sách reservationIds không được rỗng (@NotEmpty).
 * - Thao tác là idempotent: nếu một reservationId đã tồn tại trong đơn, không thêm trùng lặp.
 */
public record AttachOrderReservationsRequest(
        @NotEmpty(message = "Danh sách reservationIds không được để trống")
        List<UUID> reservationIds
) {
}
