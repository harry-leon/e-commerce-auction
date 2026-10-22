package com.nexus.commerce.enums;

/**
 * Trạng thái của giỏ hàng theo ràng buộc chk_cart_status trong cơ sở dữ liệu (V2__init_schema.sql).
 * - ACTIVE: Giỏ hàng đang được người mua sử dụng (mỗi user tối đa 1 active cart theo uq_user_active_cart).
 * - MERGED: Giỏ hàng khách vãng lai đã gộp vào tài khoản người dùng sau khi đăng nhập.
 * - EXPIRED: Giỏ hàng hết hạn (sau 7 ngày không có hoạt động tương tác).
 * - CONVERTED: Giỏ hàng đã chuyển đổi thành đơn hàng thành công sau khi checkout.
 */
public enum CartStatus {
    ACTIVE,
    MERGED,
    EXPIRED,
    CONVERTED
}
