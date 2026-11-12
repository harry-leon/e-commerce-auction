package com.nexus.auction.client.dto;

import java.util.UUID;

/**
 * Phản hồi kiểm tra điều kiện uy tín trả về từ reputation-service.
 */
public record ReputationEligibilityDto(
        UUID userId,
        String action,
        boolean eligible,
        int score,
        int minRequiredScore,
        String trustLevel,
        String reason
) {
}
