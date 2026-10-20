package com.nexus.fulfillment.enums;

/**
 * Loại thực thể tham chiếu phát sinh phiếu giữ hàng.
 *
 * Chức năng: Phân loại nguồn gốc của yêu cầu giữ hàng theo ràng buộc DB chk_reservation_ref_type.
 * Giá trị:
 * - ORDER: Giữ hàng cho quy trình mua sắm / checkout đơn hàng thông thường.
 * - AUCTION: Giữ hàng cho người thắng phiên đấu giá (Auction Settlement) theo SRS §3.5.3 & §3.6.1.
 */
public enum ReservationRefType {
    ORDER,
    AUCTION
}
