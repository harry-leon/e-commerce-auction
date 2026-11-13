package com.nexus.reputation.dto;

import com.nexus.reputation.enums.TrustLevel;
import java.util.UUID;

/**
 * Dữ liệu phản hồi tóm tắt điểm uy tín, thống kê giao dịch và chỉ số đánh giá trung bình của người dùng.
 * Giữ constructor 8 tham số tương thích ngược cho các caller và unit test hiện có.
 */
public record ReputationSummaryResponse(
        UUID userId,
        int score,
        TrustLevel trustLevel,
        int totalTx,
        int successTx,
        int failedTx,
        int auctionWon,
        int auctionFailed,
        double avgRating,
        long totalRatings
) {

    public ReputationSummaryResponse(
            UUID userId,
            int score,
            TrustLevel trustLevel,
            int totalTx,
            int successTx,
            int failedTx,
            int auctionWon,
            int auctionFailed) {
        this(userId, score, trustLevel, totalTx, successTx, failedTx, auctionWon, auctionFailed, 0.0d, 0L);
    }
}
