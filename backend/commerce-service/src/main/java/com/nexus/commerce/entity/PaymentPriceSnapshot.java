package com.nexus.commerce.entity;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ảnh chụp giá BE của một attempt, lưu JSONB cùng Payment trước khi cấp URL VNPAY.
 * Chứa ID dòng đơn và giá đã tính để IPN chốt đúng giao dịch, không đọc lại giá Catalog.
 * Không chứa token, thông tin merchant hoặc dữ liệu giá do FE gửi.
 */
public record PaymentPriceSnapshot(BigDecimal subtotal, BigDecimal shippingFee, BigDecimal discount,
                                   BigDecimal total, List<Line> items) {
    /** Sao chép danh sách để caller không thay dòng giá sau khi đã tính tổng/ký URL. */
    public PaymentPriceSnapshot {
        items = List.copyOf(items);
    }

    /**
     * Một dòng giá bất biến; orderItemId liên kết với dòng đơn cần chốt trong DB Commerce.
     * unitPrice là giá bán sau tính giá của dòng; itemTotal = unitPrice * quantity.
     */
    public record Line(UUID orderItemId, UUID skuId, int quantity, BigDecimal unitPrice, BigDecimal itemTotal) { }
}
