package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JSON tạo đơn: subtotal/shippingFee/discount/total là giá chính thức, null khi chưa xác nhận thu tiền.
 * estimated* là dự kiến để FE hiển thị; priceFinalized phân biệt hai loại giá.
 * paymentUrl null khi tạo mới; gọi POST /payments để lấy amount/snapshot và URL VNPAY.
 */
public record CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal,
                                  BigDecimal shippingFee, BigDecimal discount, BigDecimal total, String paymentMethod,
                                  String paymentUrl, BigDecimal estimatedSubtotal, BigDecimal estimatedShippingFee,
                                  BigDecimal estimatedDiscount, BigDecimal estimatedTotal, boolean priceFinalized) {
    /** Constructor contract cũ cho caller/test lịch sử; luồng tạo mới phải truyền cờ và dự kiến rõ ràng. */
    public CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal, BigDecimal shippingFee,
                               BigDecimal discount, BigDecimal total, String paymentMethod, String paymentUrl) {
        this(id, orderNo, status, subtotal, shippingFee, discount, total, paymentMethod, paymentUrl,
                subtotal, shippingFee, discount, total, true);
    }
    /** Giữ tương thích caller COD trước khi bổ sung sandbox VNPAY. */
    public CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal, BigDecimal shippingFee,
                               BigDecimal discount, BigDecimal total, String paymentMethod) {
        this(id, orderNo, status, subtotal, shippingFee, discount, total, paymentMethod, null);
    }
}
