package com.nexus.auction.client;

import java.util.UUID;

/**
 * Cổng kiểm tra điều kiện điểm uy tín trước khi người dùng tạo phiên đấu giá hoặc đặt giá thầu (SRS §3.5.3).
 */
public interface ReputationEligibilityClient {

    /** Kiểm tra tư cách thực hiện hành động (BID hoặc CREATE_AUCTION), ném REPUTATION_ACTION_FORBIDDEN nếu không đạt. */
    void assertEligible(UUID userId, String action);
}
