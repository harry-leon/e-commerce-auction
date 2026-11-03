package com.nexus.fulfillment.event;

import com.nexus.fulfillment.enums.ReservationRefType;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi hàng tồn kho được xác nhận xuất kho (SRS §2.8 InventoryCommitted).
 *
 * Chức năng:
 * - Thông báo khi đơn hàng đã thanh toán thành công, số lượng giữ hàng được chuyển thành xuất kho (OUTBOUND).
 */
public record InventoryCommittedEvent(
        UUID reservationId,
        UUID skuId,
        UUID warehouseId,
        int quantity,
        ReservationRefType refType,
        UUID refId,
        Instant occurredAt
) {
    public static InventoryCommittedEvent of(
            UUID reservationId,
            UUID skuId,
            UUID warehouseId,
            int quantity,
            ReservationRefType refType,
            UUID refId
    ) {
        return new InventoryCommittedEvent(
                reservationId,
                skuId,
                warehouseId,
                quantity,
                refType,
                refId,
                Instant.now()
        );
    }
}
