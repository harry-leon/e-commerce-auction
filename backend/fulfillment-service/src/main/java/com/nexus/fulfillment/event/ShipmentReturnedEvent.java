package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi kiện hàng bị hoàn trả về kho xuất (SRS §2.8 ShipmentReturned, KH-S3-2).
 *
 * Chức năng:
 * - Thông báo cho kho chuẩn bị tiếp nhận hàng trả về và Commerce Service tiến hành luồng hoàn tiền/xử lý trả hàng.
 * - Đầu vào: shipmentId, orderId, carrierCode, trackingNumber, reason và occurredAt.
 */
public record ShipmentReturnedEvent(
        UUID shipmentId,
        UUID orderId,
        String carrierCode,
        String trackingNumber,
        String reason,
        Instant occurredAt
) {
    public static ShipmentReturnedEvent of(
            UUID shipmentId,
            UUID orderId,
            String carrierCode,
            String trackingNumber,
            String reason) {
        return new ShipmentReturnedEvent(
                shipmentId,
                orderId,
                carrierCode,
                trackingNumber,
                reason != null ? reason : "Shipment returned",
                Instant.now()
        );
    }
}
