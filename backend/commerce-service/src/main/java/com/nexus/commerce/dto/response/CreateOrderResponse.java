package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Kết quả đã lưu DB; đơn VNPAY kèm URL đã ký, COD có paymentUrl null; replay không tạo payment mới. */
public record CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal,
                                  BigDecimal shippingFee, BigDecimal discount, BigDecimal total, String paymentMethod,
                                  String paymentUrl) {
    /** Giữ tương thích caller COD trước khi bổ sung sandbox VNPAY. */
    public CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal, BigDecimal shippingFee,
                               BigDecimal discount, BigDecimal total, String paymentMethod) {
        this(id, orderNo, status, subtotal, shippingFee, discount, total, paymentMethod, null);
    }
}
