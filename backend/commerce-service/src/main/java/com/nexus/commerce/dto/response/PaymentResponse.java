package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dữ liệu buyer được phép đọc; không lộ merchant secret hoặc payload callback thô.
 * EXPIRED nghĩa là URL đã hết hạn, paymentUrl=null; chưa phải kết luận giao dịch thất bại.
 * Khi chưa có IPN xác nhận, trạng thái DB vẫn PENDING để nhận kết quả đến chậm; GET không hủy đơn.
 * PROCESSING nghĩa là đã thu tiền/chờ commit kho; REQUIRES_REVIEW cần hỗ trợ đối soát, chưa tự hoàn tiền và không trả lần hai.
 * amount/priceSnapshot là tiền và giá BE đã gửi cổng, có ngay từ PENDING; không phải bằng chứng đã thu tiền.
 * priceSnapshot.items chứa orderItemId, skuId, quantity, unitPrice, itemTotal; IPN chốt chính các giá này.
 * inventoryRefId dùng đối chiếu ledger kho: paymentId với REGULAR mới, orderId với AUCTION/legacy.
 */
public record PaymentResponse(UUID paymentId, UUID orderId, String status, BigDecimal amount,
                              String currency, String paymentUrl, Instant expiresAt,
                              com.nexus.commerce.entity.PaymentPriceSnapshot priceSnapshot, UUID inventoryRefId) {
    /** Giữ constructor caller cũ; response runtime luôn có snapshot BE dùng để đối soát và chốt giá. */
    public PaymentResponse(UUID paymentId, UUID orderId, String status, BigDecimal amount,
                           String currency, String paymentUrl, Instant expiresAt) {
        this(paymentId, orderId, status, amount, currency, paymentUrl, expiresAt, null, null);
    }
}
