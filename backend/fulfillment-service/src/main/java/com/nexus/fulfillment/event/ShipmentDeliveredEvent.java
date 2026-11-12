package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi kiện hàng đã được giao thành công cho người nhận (SRS §2.8 ShipmentDelivered, KH-S3-2).
 *
 * Chức năng:
 * - Kích hoạt hoàn tất đơn hàng trong Commerce Service, bắt đầu đếm thời gian xác nhận tự động (autoConfirmDays)
 *   hoặc giải ngân tiền cho Seller.
 * - Đầu vào: shipmentId, orderId, carrierCode, trackingNumber, deliveredAt và occurredAt.
 */
public record ShipmentDeliveredEvent(
        UUID shipmentId,
        UUID orderId,
        String carrierCode,
        String trackingNumber,
        Instant deliveredAt,
        Instant occurredAt
) {
    public static ShipmentDeliveredEvent of(
            UUID shipmentId,
            UUID orderId,
            String carrierCode,
            String trackingNumber,
            Instant deliveredAt) {
        return new ShipmentDeliveredEvent(
                shipmentId,
                orderId,
                carrierCode,
                trackingNumber,
                deliveredAt != null ? deliveredAt : Instant.now(),
                Instant.now()
        );
    }
}
