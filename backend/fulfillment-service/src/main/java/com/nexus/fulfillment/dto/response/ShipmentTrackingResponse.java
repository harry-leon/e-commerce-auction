package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phản hồi tra cứu hành trình vận đơn thời gian thực kèm lịch sử chuyển trạng thái (SRS §3.6.2).
 */
public record ShipmentTrackingResponse(
        UUID shipmentId,
        UUID orderId,
        String carrier,
        String trackingNumber,
        ShipmentStatus status,
        String carrierStatus,
        String currentLocation,
        Instant estimatedDeliveryAt,
        List<TrackingHistoryItem> history
) {
    /** Một mốc lịch sử trạng thái đã chuẩn hóa trong hệ thống. */
    public record TrackingHistoryItem(
            String carrierStatus,
            ShipmentStatus internalStatus,
            Instant occurredAt
    ) {
    }
}
