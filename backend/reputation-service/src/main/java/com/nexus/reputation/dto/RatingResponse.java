package com.nexus.reputation.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Dữ liệu phản hồi của một bản ghi đánh giá giữa Buyer và Seller.
 */
public record RatingResponse(
        UUID id,
        UUID orderId,
        UUID raterId,
        UUID rateeId,
        int score,
        String comment,
        boolean isAggregated,
        boolean isDisputed,
        Instant createdAt
) {
}
