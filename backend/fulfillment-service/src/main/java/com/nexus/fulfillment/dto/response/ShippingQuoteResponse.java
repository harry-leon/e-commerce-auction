package com.nexus.fulfillment.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Kết quả báo giá vận chuyển tổng hợp từ các hãng được hỗ trợ (SRS §3.6.2).
 */
public record ShippingQuoteResponse(
        UUID orderId,
        UUID warehouseId,
        String defaultCarrier,
        BigDecimal freeShippingThreshold,
        boolean freeShippingApplied,
        List<ShippingQuoteOptionResponse> options
) {
}
