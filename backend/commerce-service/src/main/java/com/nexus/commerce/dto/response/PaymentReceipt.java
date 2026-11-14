package com.nexus.commerce.dto.response;

import com.nexus.commerce.entity.PaymentPriceSnapshot;
import java.time.Instant;
import java.util.UUID;

/**
 * Biên nhận nội bộ chỉ chứa snapshot giá của attempt đã thu tiền, không phải hóa đơn thuế.
 * Không lấy giá Catalog/Order hiện tại hoặc nhúng merchant, token, địa chỉ và số điện thoại.
 * ID và thời điểm từ Payment giữ ổn định khi tải lại; refund không sửa số tiền thu ban đầu.
 */
public record PaymentReceipt(int version, String documentType, String receiptId, UUID paymentId,
                             UUID orderId, Instant paidAt, String currency, String method,
                             PaymentPriceSnapshot prices) { }
