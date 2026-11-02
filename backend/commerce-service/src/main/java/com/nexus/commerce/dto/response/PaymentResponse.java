package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dữ liệu buyer được phép đọc; không lộ merchant secret hoặc payload callback thô. */
public record PaymentResponse(UUID paymentId, UUID orderId, String status, BigDecimal amount,
                              String currency, String paymentUrl, Instant expiresAt) { }
