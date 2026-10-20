package com.nexus.fulfillment.enums;

/**
 * Trạng thái của phiếu giữ hàng tồn kho (Inventory Reservation).
 *
 * Chức năng: Định nghĩa vòng đời của một phiếu giữ hàng theo SRS §3.6.1 và DB schema V2.
 * Quy tắc:
 * - PENDING: Phiếu vừa tạo khi checkout, đang giữ số lượng tồn kho tạm thời.
 * - CONFIRMED: Đã thanh toán thành công, xác nhận xuất kho (InventoryCommitted).
 * - RELEASED: Đơn hàng bị hủy hoặc người dùng hủy checkout, hoàn trả tồn kho (InventoryReleased).
 * - EXPIRED: Hết hạn thời gian giữ (sau 30 phút theo cấu hình INVENTORY_RESERVATION_TIMEOUT_MINUTES).
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    RELEASED,
    EXPIRED
}
