package com.nexus.fulfillment.event;

import com.nexus.fulfillment.enums.ReservationRefType;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi một lệnh giữ hàng được giải phóng và hoàn trả lại kho (SRS §2.8 InventoryReleased).
 *
 * Chức năng:
 * - Thông báo khi người dùng hủy đơn, thanh toán thất bại hoặc lệnh giữ kho được mở khóa.
 */
public record InventoryReleasedEvent(
        UUID reservationId,
        UUID skuId,
        UUID warehouseId,
        int quantity,
        ReservationRefType refType,
        UUID refId,
        String reason,
        Instant occurredAt
) {
    public static InventoryReleasedEvent of(
            UUID reservationId,
            UUID skuId,
            UUID warehouseId,
            int quantity,
            ReservationRefType refType,
            UUID refId,
            String reason
    ) {
        return new InventoryReleasedEvent(
                reservationId,
                skuId,
                warehouseId,
                quantity,
                refType,
                refId,
                reason,
                Instant.now()
        );
    }
}
