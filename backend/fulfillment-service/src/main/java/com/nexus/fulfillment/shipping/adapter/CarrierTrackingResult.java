package com.nexus.fulfillment.shipping.adapter;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.time.Instant;
import java.util.List;

/**
 * Kết quả tra cứu hành trình vận đơn chuẩn hóa trả về từ Carrier Adapter.
 */
public record CarrierTrackingResult(
        String carrierCode,
        String trackingNumber,
        String carrierStatus,
        ShipmentStatus mappedStatus,
        String currentLocation,
        Instant updatedAt,
        List<CarrierTrackingEvent> events
) {
}
