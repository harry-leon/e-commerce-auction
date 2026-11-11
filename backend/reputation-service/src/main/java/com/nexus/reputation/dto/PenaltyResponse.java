package com.nexus.reputation.dto;

import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import java.time.Instant;
import java.util.UUID;

/** Phản hồi kết quả trừ điểm uy tín, phân biệt lần ghi mới với bản phát lại lũy nghiệm. */
public record PenaltyResponse(
        UUID penaltyId,
        UUID userId,
        PenaltyRefType refType,
        UUID refId,
        int scoreDelta,
        int previousScore,
        int newScore,
        TrustLevel trustLevel,
        String reason,
        boolean idempotentReplay,
        Instant createdAt
) {
}
