package com.nexus.fulfillment.enums;

/**
 * Định nghĩa loại nghiệp vụ nguồn gốc yêu cầu giữ hàng tồn kho.
 *
 * Chức năng:
 * - Phân loại yêu cầu giữ hàng từ đơn hàng thương mại điện tử (ORDER) hay phiên đấu giá (AUCTION).
 * - Khớp với CHECK constraint 'chk_reservation_ref_type' trong bảng inventory_reservations.
 */
public enum ReservationRefType {
    /** Giữ hàng phục vụ quy trình Checkout đơn hàng từ Commerce Service. */
    ORDER,

    /** Giữ hàng phục vụ niêm yết hoặc kết thúc phiên đấu giá từ Auction Service. */
    AUCTION
}
