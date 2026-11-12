package com.nexus.fulfillment.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi hệ thống thực hiện yêu cầu báo giá cước vận chuyển (SRS §2.8 ShippingQuoteRequested).
 *
 * Chức năng:
 * - Ghi nhận nhu cầu báo giá cho đơn hàng hoặc phiên ước tính phí ship trước checkout.
 * - Phục vụ theo dõi hành vi báo giá và đối soát lựa chọn hãng vận chuyển.
 */
public record ShippingQuoteRequestedEvent(
        UUID requestId,
        UUID orderId,
        UUID warehouseId,
        String requestedCarrier,
        BigDecimal orderTotal,
        Integer weightGrams,
        Instant occurredAt
) {
    public static ShippingQuoteRequestedEvent of(
            UUID orderId,
            UUID warehouseId,
            String requestedCarrier,
            BigDecimal orderTotal,
            Integer weightGrams) {
        return new ShippingQuoteRequestedEvent(
                UUID.randomUUID(),
                orderId,
                warehouseId,
                requestedCarrier,
                orderTotal,
                weightGrams,
                Instant.now()
        );
    }
}
