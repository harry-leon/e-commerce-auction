package com.nexus.fulfillment.enums;

/**
 * Định nghĩa các loại hình kho hàng trong hệ thống Fulfillment.
 *
 * Chức năng:
 * - Phân loại kho theo mục đích lưu trữ và xử lý vận đơn.
 * - Khớp với CHECK constraint 'chk_warehouse_type' trong bảng warehouses.
 */
public enum WarehouseType {
    /** Kho tiêu chuẩn lưu trữ hàng hóa thông thường. */
    STANDARD,

    /** Kho ngoại quan lưu hàng chưa hoàn tất thủ tục hải quan. */
    BONDED,

    /** Kho tiếp nhận và xử lý hàng hoàn trả. */
    RETURN,

    /** Kho trung chuyển hàng hóa không lưu kho lâu dài. */
    CROSS_DOCK
}
