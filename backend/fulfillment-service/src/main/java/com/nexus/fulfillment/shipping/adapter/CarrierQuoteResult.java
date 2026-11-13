package com.nexus.fulfillment.shipping.adapter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Kết quả báo giá chuẩn hóa trả về từ một Carrier Adapter.
 */
public record CarrierQuoteResult(
        String carrierCode,
        String serviceType,
        BigDecimal fee,
        int estimatedDeliveryDays,
        Instant estimatedDeliveryAt
) {
}
