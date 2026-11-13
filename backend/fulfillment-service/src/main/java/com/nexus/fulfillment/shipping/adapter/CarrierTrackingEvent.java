package com.nexus.fulfillment.shipping.adapter;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.time.Instant;

/**
 * Một mốc sự kiện hành trình giao hàng trả về từ hãng vận chuyển.
 */
public record CarrierTrackingEvent(
        String carrierStatus,
        ShipmentStatus mappedStatus,
        String description,
        String location,
        Instant occurredAt
) {
}
