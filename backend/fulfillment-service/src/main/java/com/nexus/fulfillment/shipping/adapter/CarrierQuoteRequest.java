package com.nexus.fulfillment.shipping.adapter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dữ liệu đầu vào chuẩn hóa gửi tới Carrier Adapter để tính phí vận chuyển.
 */
public record CarrierQuoteRequest(
        UUID orderId,
        UUID warehouseId,
        String fromAddress,
        String toAddress,
        Integer toDistrictId,
        String toWardCode,
        Integer weightGrams,
        BigDecimal orderTotal,
        String serviceType
) {
}
