package com.nexus.commerce.enums;

/**
 * Định nghĩa nguồn gốc phát sinh đơn hàng.
 *
 * <p>Quy chuẩn theo ràng buộc cơ sở dữ liệu `chk_order_source_type` trong `nexus_schema.sql`:
 * <ul>
 *   <li>REGULAR: Đơn hàng mua trực tiếp thông qua giỏ hàng thương mại điện tử.</li>
 *   <li>AUCTION: Đơn hàng hình thành từ việc thắng phiên đấu giá (bắt buộc kèm auction_id).</li>
 * </ul>
 */
public enum OrderSourceType {

    /**
     * Mua hàng tiêu chuẩn thông qua giỏ hàng.
     */
    REGULAR,

    /**
     * Mua hàng từ kết quả đấu giá thành công.
     */
    AUCTION
}
