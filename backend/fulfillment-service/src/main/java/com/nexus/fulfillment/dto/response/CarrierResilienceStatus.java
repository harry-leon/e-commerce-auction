package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.shipping.adapter.resilience.CarrierCircuitBreakerState;
import java.time.Instant;

/**
 * DTO phản hồi trạng thái ngắt mạch (Circuit Breaker) và khả năng chịu lỗi của hãng vận chuyển (KH-S3-2).
 */
public record CarrierResilienceStatus(
        String carrierCode,
        CarrierCircuitBreakerState state,
        int consecutiveFailures,
        int totalCalls,
        int successfulCalls,
        int failureLimit,
        int retryLimit,
        long timeoutMs,
        Instant lastStateChangedAt
) {
}
