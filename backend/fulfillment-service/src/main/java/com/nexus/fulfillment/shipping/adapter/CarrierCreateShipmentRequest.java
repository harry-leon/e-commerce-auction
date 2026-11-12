package com.nexus.fulfillment.shipping.adapter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Dữ liệu đầu vào chuẩn hóa gửi tới Carrier Adapter để tạo vận đơn thực tế.
 */
public record CarrierCreateShipmentRequest(
        UUID orderId,
        UUID warehouseId,
        String fromAddress,
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        Integer toDistrictId,
        String toWardCode,
        Integer weightGrams,
        BigDecimal codAmount,
        BigDecimal insuranceValue,
        String serviceType,
        String note
) {
}
