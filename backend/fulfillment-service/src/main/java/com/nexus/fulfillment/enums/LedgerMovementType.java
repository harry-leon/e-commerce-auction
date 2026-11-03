package com.nexus.fulfillment.enums;

/**
 * Phân loại biến động kho trong Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 *
 * Chức năng:
 * - Ghi lại bản chất của từng lần thay đổi số lượng hàng tồn kho.
 * - Khớp với CHECK constraint 'chk_ledger_movement_type' trong bảng inventory_ledger.
 */
public enum LedgerMovementType {
    /** Nhập hàng vào kho (tăng total_qty). */
    INBOUND,

    /** Xuất hàng khỏi kho giao khách (giảm reserved_qty và total_qty). */
    OUTBOUND,

    /** Tạm giữ hàng khi khách checkout hoặc tạo phiên đấu giá (tăng reserved_qty, giảm available_qty). */
    RESERVE,

    /** Giải phóng hàng đã giữ hoàn trả về tồn khả dụng (giảm reserved_qty, tăng available_qty). */
    RELEASE,

    /** Điều chỉnh kiểm kê thực tế tại kho (tăng hoặc giảm total_qty do hỏng hóc, thất thoát, bù sai lệch). */
    ADJUSTMENT
}
