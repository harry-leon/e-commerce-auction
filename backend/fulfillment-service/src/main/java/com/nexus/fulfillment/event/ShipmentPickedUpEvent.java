package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi đơn vị vận chuyển đã tiếp nhận kiện hàng từ kho (SRS §2.8 ShipmentPickedUp, KH-S3-2).
 *
 * Chức năng:
 * - Thông báo cho Order/Commerce và Notification Service cập nhật trạng thái đơn hàng sang Đang giao.
 * - Đầu vào: mã vận đơn shipmentId, mã đơn hàng orderId, mã hãng carrierCode, trackingNumber và thời điểm phát sinh.
 */
public record ShipmentPickedUpEvent(
        UUID shipmentId,
        UUID orderId,
        String carrierCode,
        String trackingNumber,
        Instant occurredAt
) {
    public static ShipmentPickedUpEvent of(
            UUID shipmentId,
            UUID orderId,
            String carrierCode,
            String trackingNumber) {
        return new ShipmentPickedUpEvent(
                shipmentId,
                orderId,
                carrierCode,
                trackingNumber,
                Instant.now()
        );
    }
}
