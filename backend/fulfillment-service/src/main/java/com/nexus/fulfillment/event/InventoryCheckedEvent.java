package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi tồn kho SKU được kiểm tra tính khả dụng (SRS §2.8 InventoryChecked).
 *
 * Chức năng:
 * - Thông báo cho các thành phần quan sát biết SKU và số lượng khả dụng tại thời điểm kiểm tra.
 */
public record InventoryCheckedEvent(
        UUID skuId,
        UUID warehouseId,
        int availableQty,
        Instant occurredAt
) {
    public static InventoryCheckedEvent of(UUID skuId, UUID warehouseId, int availableQty) {
        return new InventoryCheckedEvent(skuId, warehouseId, availableQty, Instant.now());
    }
}
