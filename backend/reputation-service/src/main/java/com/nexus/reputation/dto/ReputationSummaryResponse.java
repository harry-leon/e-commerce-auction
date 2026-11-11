package com.nexus.reputation.dto;

import com.nexus.reputation.enums.TrustLevel;
import java.util.UUID;

/** Tóm tắt điểm uy tín, hạng tin cậy và thống kê giao dịch/đấu giá của người dùng. */
public record ReputationSummaryResponse(
        UUID userId,
        int score,
        TrustLevel trustLevel,
        int totalTx,
        int successTx,
        int failedTx,
        int auctionWon,
        int auctionFailed
) {
}
