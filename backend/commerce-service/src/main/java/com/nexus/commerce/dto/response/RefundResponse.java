package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.RefundStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Snapshot công khai của yêu cầu refund; không trả merchant, provider reference hoặc thông tin xác thực. */
public record RefundResponse(UUID refundId, UUID paymentId, UUID orderId, BigDecimal amount,
        String currency, RefundStatus status, Instant createdAt) { }
