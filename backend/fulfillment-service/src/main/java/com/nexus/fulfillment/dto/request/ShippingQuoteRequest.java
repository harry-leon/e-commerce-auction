package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Yêu cầu báo giá phí vận chuyển cho đơn hàng hoặc giỏ hàng (SRS §3.6.2).
 * 1. Chức năng: Chứa thông tin địa chỉ giao hàng, trọng lượng, giá trị đơn hàng và mã voucher ưu đãi.
 * 2. Đầu vào & kết quả: Dùng làm Request Body cho endpoint POST /api/v1/shipping/quotes.
 * 3. Quy tắc: Bắt buộc phải có voucherCode hợp lệ ('FREESHIP') thì mới được miễn phí cước vận chuyển.
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
        BigDecimal orderTotal,
        String voucherCode
) {
    /**
     * Constructor tương thích ngược cho các service hoặc test case không truyền voucherCode.
     */
    public ShippingQuoteRequest(
            UUID orderId,
            UUID warehouseId,
            String carrier,
            String serviceType,
            String shippingAddress,
            Integer toDistrictId,
            String toWardCode,
            Integer weightGrams,
            BigDecimal orderTotal
    ) {
        this(orderId, warehouseId, carrier, serviceType, shippingAddress, toDistrictId, toWardCode, weightGrams, orderTotal, null);
    }
}
