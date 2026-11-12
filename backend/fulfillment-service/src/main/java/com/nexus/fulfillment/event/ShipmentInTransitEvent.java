package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi kiện hàng đang trong hành trình trung chuyển giao hàng (SRS §2.8 ShipmentInTransit, KH-S3-2).
 *
 * Chức năng:
 * - Cập nhật tiến trình giao hàng theo thời gian thực cho người mua và các hệ thống liên quan.
 * - Đầu vào: shipmentId, orderId, carrierCode, trackingNumber, currentLocation và occurredAt.
 */
public record ShipmentInTransitEvent(
        UUID shipmentId,
        UUID orderId,
        String carrierCode,
        String trackingNumber,
        String currentLocation,
        Instant occurredAt
) {
    public static ShipmentInTransitEvent of(
            UUID shipmentId,
            UUID orderId,
            String carrierCode,
            String trackingNumber,
            String currentLocation) {
        return new ShipmentInTransitEvent(
                shipmentId,
                orderId,
                carrierCode,
                trackingNumber,
                currentLocation,
                Instant.now()
        );
    }
}
