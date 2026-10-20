package com.nexus.fulfillment.enums;

/**
 * Loại biến động số lượng trong sổ cái tồn kho (Inventory Ledger).
 *
 * Chức năng: Định danh hành động thay đổi tồn kho theo ràng buộc DB chk_ledger_movement_type và SRS §3.6.1.
 * Giá trị:
 * - INBOUND: Nhập kho bổ sung hàng.
 * - OUTBOUND: Xuất kho chính thức sau khi thanh toán thành công (InventoryCommitted).
 * - RESERVE: Tạm khóa tồn kho khi người dùng đặt hàng / checkout (InventoryReserved).
 * - RELEASE: Trả lại tồn khả dụng khi đơn hàng bị hủy hoặc hết hạn thanh toán (InventoryReleased).
 * - ADJUSTMENT: Điều chỉnh kiểm kê thực tế tại kho.
 */
public enum InventoryMovementType {
    INBOUND,
    OUTBOUND,
    RESERVE,
    RELEASE,
    ADJUSTMENT
}
