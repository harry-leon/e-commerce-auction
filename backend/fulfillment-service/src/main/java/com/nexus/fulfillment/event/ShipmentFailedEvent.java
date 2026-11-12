package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi giao hàng thất bại hoặc gặp sự cố giao vận (SRS §2.8 ShipmentFailed, KH-S3-2).
 *
 * Chức năng:
 * - Thông báo cho bộ phận chăm sóc khách hàng và hệ thống Commerce để lên phương án giao lại hoặc hỗ trợ giải quyết sự cố.
 * - Đầu vào: shipmentId, orderId, carrierCode, trackingNumber, reason và occurredAt.
 */
public record ShipmentFailedEvent(
        UUID shipmentId,
        UUID orderId,
        String carrierCode,
        String trackingNumber,
        String reason,
        Instant occurredAt
) {
    public static ShipmentFailedEvent of(
            UUID shipmentId,
            UUID orderId,
            String carrierCode,
            String trackingNumber,
            String reason) {
        return new ShipmentFailedEvent(
                shipmentId,
                orderId,
                carrierCode,
                trackingNumber,
                reason != null ? reason : "Delivery failed",
                Instant.now()
        );
    }
}
