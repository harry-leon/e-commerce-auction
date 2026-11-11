package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.LedgerMovementType;
import java.time.Instant;
import java.util.UUID;

/**
 * Phản hồi chi tiết một bản ghi trong Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 *
 * @param id            Mã bản ghi sổ cái
 * @param skuId         Mã biến thể SKU
 * @param warehouseId   Mã kho
 * @param warehouseCode Mã nghiệp vụ của kho (ví dụ WH_001)
 * @param movementType  Loại biến động (INBOUND, OUTBOUND, RESERVE, RELEASE, ADJUSTMENT)
 * @param quantity      Số lượng biến động (khác 0)
 * @param refType       Loại chứng từ tham chiếu
 * @param refId         Mã đơn hàng, phiên đấu giá hoặc phiếu điều chỉnh
 * @param reason        Lý do nghiệp vụ hoặc ghi chú biến động
 * @param actorId       Mã người thao tác nếu phát sinh từ thao tác quản trị/seller
 * @param createdAt     Mốc thời gian phát sinh biến động
 */
public record InventoryLedgerResponse(
        UUID id,
        UUID skuId,
        UUID warehouseId,
        String warehouseCode,
        LedgerMovementType movementType,
        int quantity,
        String refType,
        UUID refId,
        String reason,
        UUID actorId,
        Instant createdAt
) {
    /**
     * Constructor tương thích ngược với contract 8 trường ban đầu.
     */
    public InventoryLedgerResponse(
            UUID id,
            UUID skuId,
            UUID warehouseId,
            LedgerMovementType movementType,
            int quantity,
            String refType,
            UUID refId,
            Instant createdAt
    ) {
        this(id, skuId, warehouseId, null, movementType, quantity, refType, refId, null, null, createdAt);
    }
}
