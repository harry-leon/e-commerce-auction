package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Biên nhận hoàn tiền nội bộ liên kết PAY-{paymentId}, không sửa document thu gốc hoặc giả hóa đơn thuế.
 * recordedAt là lúc registry ghi nhận bằng chứng, không suy đoán thời điểm ngân hàng ghi có.
 * evidenceSource phân biệt PROVIDER với MANUAL; không xuất ghi chú/bằng chứng nội bộ hay merchant reference.
 */
public record RefundReceipt(int version, String documentType, String receiptId, UUID refundId,
                            String originalReceiptId, UUID paymentId, UUID orderId, BigDecimal amount,
                            String currency, Instant recordedAt, String evidenceSource) { }
