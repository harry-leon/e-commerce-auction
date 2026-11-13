package com.nexus.fulfillment.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một phương án báo giá vận chuyển từ từng hãng đối tác, đã áp dụng quy tắc miễn phí vận chuyển.
 */
public record ShippingQuoteOptionResponse(
        String carrier,
        String serviceType,
        BigDecimal shippingFee,
        BigDecimal originalFee,
        boolean freeShippingApplied,
        int estimatedDeliveryDays,
        Instant estimatedDeliveryAt
) {
}
