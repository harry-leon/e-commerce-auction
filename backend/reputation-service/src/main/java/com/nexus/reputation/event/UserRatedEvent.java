package com.nexus.reputation.event;

import com.nexus.reputation.enums.TrustLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện phát hành sau khi commit giao dịch đánh giá giữa hai bên mua/bán.
 */
public record UserRatedEvent(
        UUID ratingId,
        UUID orderId,
        UUID raterId,
        UUID rateeId,
        int starScore,
        int scoreDelta,
        int previousScore,
        int newScore,
        TrustLevel trustLevel,
        Instant occurredAt
) {
}
