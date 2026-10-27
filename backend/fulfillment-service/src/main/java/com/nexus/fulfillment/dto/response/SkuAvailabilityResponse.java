package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Kết quả kiểm tra tồn kho khả dụng cho một biến thể SKU (SRS §3.6.1).
 *
 * @param skuId        Mã SKU
 * @param warehouseId  Mã kho kiểm tra
 * @param availableQty Số lượng tồn khả dụng còn lại
 * @param inStock      True nếu availableQty > 0
 */
public record SkuAvailabilityResponse(
        UUID skuId,
        UUID warehouseId,
        int availableQty,
        boolean inStock
) {
}
