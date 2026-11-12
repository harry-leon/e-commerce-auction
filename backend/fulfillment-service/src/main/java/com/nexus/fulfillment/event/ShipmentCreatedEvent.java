package com.nexus.fulfillment.event;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi vận đơn được tạo thành công và đã có mã vận đơn từ hãng (SRS §2.8 ShipmentCreated).
 *
 * Chức năng:
 * - Thông báo cho Commerce/Notification Service biết đơn hàng đã có mã vận đơn để cập nhật trạng thái và gửi thông báo.
 */
public record ShipmentCreatedEvent(
        UUID shipmentId,
        UUID orderId,
        UUID warehouseId,
        String carrier,
        String serviceType,
        BigDecimal shippingFee,
        String trackingNumber,
        ShipmentStatus status,
        Instant occurredAt
) {
    public static ShipmentCreatedEvent of(
            UUID shipmentId,
            UUID orderId,
            UUID warehouseId,
            String carrier,
            String serviceType,
            BigDecimal shippingFee,
            String trackingNumber,
            ShipmentStatus status) {
        return new ShipmentCreatedEvent(
                shipmentId,
                orderId,
                warehouseId,
                carrier,
                serviceType,
                shippingFee,
                trackingNumber,
                status,
                Instant.now()
        );
    }
}
