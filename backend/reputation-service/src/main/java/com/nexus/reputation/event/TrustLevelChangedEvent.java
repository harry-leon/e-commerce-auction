package com.nexus.reputation.event;

import com.nexus.reputation.enums.TrustLevel;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện phát hành khi cấp độ tin cậy (TrustLevel) của người dùng chuyển hạng sau biến động điểm.
 */
public record TrustLevelChangedEvent(
        UUID userId,
        TrustLevel previousLevel,
        TrustLevel newLevel,
        int newScore,
        Instant occurredAt
) {
}
