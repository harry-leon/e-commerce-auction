package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dữ liệu buyer được phép đọc; không lộ merchant secret hoặc payload callback thô.
 * EXPIRED nghĩa là URL đã hết hạn, paymentUrl=null; chưa phải kết luận giao dịch thất bại.
 * Khi chưa có IPN xác nhận, trạng thái DB vẫn PENDING để nhận kết quả đến chậm; GET không hủy đơn.
 * PROCESSING nghĩa là đã thu tiền/chờ commit kho; REQUIRES_REVIEW cần hỗ trợ đối soát, chưa tự hoàn tiền và không trả lần hai.
 */
public record PaymentResponse(UUID paymentId, UUID orderId, String status, BigDecimal amount,
                              String currency, String paymentUrl, Instant expiresAt) { }
