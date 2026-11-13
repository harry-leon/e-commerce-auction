package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Yêu cầu tạo vận đơn thủ công hoặc tự động sau khi đơn hàng đã thanh toán (SRS §3.6.2).
 */
public record CreateShipmentRequest(
        @NotNull(message = "orderId không được để trống")
        UUID orderId,
        UUID warehouseId,
        String carrier,
        String serviceType,
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        Integer toDistrictId,
        String toWardCode,
        @Min(value = 1, message = "Trọng lượng gói hàng tối thiểu phải là 1 gram")
        Integer weightGrams,
        @DecimalMin(value = "0", message = "Giá trị đơn hàng không được âm")
        BigDecimal orderTotal,
        @DecimalMin(value = "0", message = "Số tiền thu hộ COD không được âm")
        BigDecimal codAmount,
        String note
) {
}
