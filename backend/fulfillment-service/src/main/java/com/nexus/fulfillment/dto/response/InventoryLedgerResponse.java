package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.LedgerMovementType;
import java.time.Instant;
import java.util.UUID;

/**
 * Phản hồi chi tiết một bản ghi trong Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 *
 * @param id           Mã bản ghi sổ cái
 * @param skuId        Mã biến thể SKU
 * @param warehouseId  Mã kho
 * @param movementType Loại biến động (INBOUND, OUTBOUND, RESERVE, RELEASE, ADJUSTMENT)
 * @param quantity     Số lượng biến động (khác 0)
 * @param refType      Loại chứng từ tham chiếu
 * @param refId        Mã đơn hàng, phiên đấu giá hoặc phiếu điều chỉnh
 * @param createdAt    Mốc thời gian phát sinh biến động
 */
public record InventoryLedgerResponse(
        UUID id,
        UUID skuId,
        UUID warehouseId,
        LedgerMovementType movementType,
        int quantity,
        String refType,
        UUID refId,
        Instant createdAt
) {
}
