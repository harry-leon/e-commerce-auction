package com.nexus.commerce.enums;

/**
 * Nguồn gốc phát sinh đơn hàng theo ràng buộc chk_order_source_type (V2__init_schema.sql).
 *
 * <p>Các phân loại nguồn đơn:
 * <ul>
 *   <li>REGULAR: Đơn hàng mua sắm trực tiếp thông qua giỏ hàng và thanh toán thông thường.</li>
 *   <li>AUCTION: Đơn hàng hình thành từ việc người dùng chiến thắng phiên đấu giá trực tuyến (bắt buộc kèm auction_id).</li>
 * </ul>
 */
public enum OrderSourceType {

    /** Đơn hàng mua sắm trực tiếp qua catalog hoặc giỏ hàng thông thường. */
    REGULAR,

    /** Đơn hàng trúng đấu giá từ phân hệ đấu giá trực tuyến (Auction Service). */
    AUCTION
}
