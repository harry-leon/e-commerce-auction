package com.nexus.reputation.event;

import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import java.time.Instant;
import java.util.UUID;

/** Sự kiện miền ReputationPenaltyApplied (SRS §2.8) phát sinh khi trừ điểm uy tín thành công. */
public record ReputationPenaltyAppliedEvent(
        UUID penaltyId,
        UUID userId,
        PenaltyRefType refType,
        UUID refId,
        int scoreDelta,
        int previousScore,
        int newScore,
        TrustLevel trustLevel,
        String reason,
        Instant occurredAt
) {
}
