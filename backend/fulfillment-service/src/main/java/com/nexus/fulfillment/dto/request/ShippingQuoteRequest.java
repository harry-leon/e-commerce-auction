package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Yêu cầu báo giá phí vận chuyển cho đơn hàng hoặc giỏ hàng (SRS §3.6.2).
 */
public record ShippingQuoteRequest(
        UUID orderId,
        UUID warehouseId,
        String carrier,
        String serviceType,
        String shippingAddress,
        Integer toDistrictId,
        String toWardCode,
        @Min(value = 1, message = "Trọng lượng gói hàng tối thiểu phải là 1 gram")
        Integer weightGrams,
        @DecimalMin(value = "0", message = "Giá trị đơn hàng không được âm")
        BigDecimal orderTotal
) {
}
