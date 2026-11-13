package com.nexus.reputation.event;

import com.nexus.reputation.enums.TrustLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện phát hành sau khi điểm uy tín của người dùng thay đổi (từ đánh giá, xử phạt hoặc admin điều chỉnh).
 */
public record ReputationScoreUpdatedEvent(
        UUID userId,
        String source,
        int scoreDelta,
        int previousScore,
        int newScore,
        TrustLevel trustLevel,
        Instant occurredAt
) {
}
