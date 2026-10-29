package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Kết quả đã lưu DB; trả cùng snapshot khi retry key/payload cũ, chưa khởi tạo cổng thanh toán online. */
public record CreateOrderResponse(UUID id, String orderNo, String status, BigDecimal subtotal,
                                  BigDecimal shippingFee, BigDecimal discount, BigDecimal total, String paymentMethod) { }
