package com.nexus.reputation.dto;

import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.TrustLevel;
import java.util.UUID;

/**
 * Kết quả kiểm tra tư cách tham gia đấu giá dựa trên điểm uy tín hiện tại của người dùng.
 */
public record ReputationEligibilityResponse(
        UUID userId,
        EligibilityAction action,
        boolean eligible,
        int score,
        int minRequiredScore,
        TrustLevel trustLevel,
        String reason
) {
}
