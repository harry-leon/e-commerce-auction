package com.nexus.fulfillment.enums;

/**
 * Trạng thái của phiếu giữ hàng tồn kho (Inventory Reservation).
 *
 * Chức năng:
 * - Theo dõi vòng đời của một lệnh giữ kho từ khi tạo đến khi thanh toán thành công hoặc hoàn hủy.
 * - Khớp với CHECK constraint 'chk_reservation_status' trong bảng inventory_reservations.
 *
 * Vòng đời:
 * - PENDING: Mới tạo khi bắt đầu checkout, giữ hàng tạm thời trong thời gian timeout (30 phút).
 * - CONFIRMED: Đơn hàng thanh toán thành công, lệnh giữ hàng được chốt để chuẩn bị xuất kho.
 * - RELEASED: Đơn hàng bị hủy chủ động, người dùng không mua hoặc thanh toán thất bại, hoàn kho lại available_qty.
 * - EXPIRED: Hết hạn 30 phút mà chưa thanh toán, hệ thống tự động quét và hoàn trả available_qty.
 */
public enum ReservationStatus {
    /** Đang tạm giữ hàng chờ thanh toán. */
    PENDING,

    /** Đã xác nhận thành công sau thanh toán. */
    CONFIRMED,

    /** Đã giải phóng chủ động do hủy đơn hoặc thanh toán lỗi. */
    RELEASED,

    /** Đã hết hạn giữ hàng do quá thời gian quy định (30 phút). */
    EXPIRED
}
