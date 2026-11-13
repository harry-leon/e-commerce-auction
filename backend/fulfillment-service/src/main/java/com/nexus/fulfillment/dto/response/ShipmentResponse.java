package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.ShipmentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Thông tin chi tiết một vận đơn trong Fulfillment Service (SRS §3.6.2).
 */
public record ShipmentResponse(
        UUID id,
        UUID orderId,
        UUID warehouseId,
        String warehouseCode,
        String carrier,
        String serviceType,
        BigDecimal shippingFee,
        boolean freeShippingApplied,
        String trackingNumber,
        ShipmentStatus status,
        Integer estimatedDeliveryDays,
        Instant estimatedDeliveryAt,
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        Instant createdAt,
        Instant updatedAt
) {
}
