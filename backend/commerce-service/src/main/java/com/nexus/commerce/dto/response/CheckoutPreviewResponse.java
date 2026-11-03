package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Giá live do server tính; kho chỉ được đảm bảo sau reserve tại bước tạo đơn. VAT hiện đã nằm trong giá. */
public record CheckoutPreviewResponse(
        BigDecimal subtotal, BigDecimal discountAmount, BigDecimal shippingFee, BigDecimal taxAmount,
        BigDecimal totalAmount, Object appliedVoucher, List<Line> items, boolean isOrderable, List<String> warnings
) {
    /** Snapshot hiển thị trước xác nhận, kèm giá tại giỏ để UI giải thích thay đổi giá. */
    public record Line(UUID skuId, UUID sellerId, String productName, String skuCode, BigDecimal unitPrice,
                       BigDecimal cartUnitPrice, int quantity, BigDecimal itemTotal) { }
}
