package com.nexus.fulfillment.event;

import com.nexus.fulfillment.enums.ReservationRefType;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi hàng tồn kho được khóa giữ thành công cho đơn hàng (SRS §2.8 InventoryReserved).
 *
 * Chức năng:
 * - Thông báo việc khóa giữ hàng thành công cho các luồng xử lý tiếp theo (như Checkout, thanh toán).
 */
public record InventoryReservedEvent(
        UUID reservationId,
        UUID skuId,
        UUID warehouseId,
        int quantity,
        ReservationRefType refType,
        UUID refId,
        Instant expiresAt,
        Instant occurredAt
) {
    public static InventoryReservedEvent of(
            UUID reservationId,
            UUID skuId,
            UUID warehouseId,
            int quantity,
            ReservationRefType refType,
            UUID refId,
            Instant expiresAt
    ) {
        return new InventoryReservedEvent(
                reservationId,
                skuId,
                warehouseId,
                quantity,
                refType,
                refId,
                expiresAt,
                Instant.now()
        );
    }
}
