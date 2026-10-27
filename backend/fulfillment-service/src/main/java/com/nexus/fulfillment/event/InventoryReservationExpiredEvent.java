package com.nexus.fulfillment.event;

import com.nexus.fulfillment.enums.ReservationRefType;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi một lệnh giữ hàng hết hạn và hệ thống tự động hoàn kho (SRS §2.8 InventoryReservationExpired).
 *
 * Chức năng:
 * - Thông báo việc một phiếu giữ hàng đã hết hạn sau thời gian timeout (30 phút).
 * - Cung cấp thông tin SKU và số lượng đã được tự động hoàn lại vào available_qty.
 */
public record InventoryReservationExpiredEvent(
        UUID reservationId,
        UUID skuId,
        UUID warehouseId,
        int quantity,
        ReservationRefType refType,
        UUID refId,
        Instant occurredAt
) {
    public static InventoryReservationExpiredEvent of(
            UUID reservationId,
            UUID skuId,
            UUID warehouseId,
            int quantity,
            ReservationRefType refType,
            UUID refId
    ) {
        return new InventoryReservationExpiredEvent(
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
