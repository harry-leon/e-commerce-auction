package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Phản hồi chi tiết 4 trạng thái tồn kho của SKU theo từng kho hàng (SRS §3.6.1).
 *
 * @param skuId          Mã định danh biến thể SKU
 * @param warehouseId    Mã định danh kho hàng
 * @param warehouseCode  Mã nghiệp vụ của kho (ví dụ WH_001)
 * @param totalQty       Tổng số lượng tồn thực tế trong kho
 * @param availableQty   Số lượng khả dụng để bán (= total - reserved - unavailable)
 * @param reservedQty    Số lượng đang bị tạm giữ bởi các đơn Checkout
 * @param unavailableQty Số lượng hàng hỏng hóc, cách ly không được bán
 */
public record InventoryStockResponse(
        UUID skuId,
        UUID warehouseId,
        String warehouseCode,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty
) {
}
