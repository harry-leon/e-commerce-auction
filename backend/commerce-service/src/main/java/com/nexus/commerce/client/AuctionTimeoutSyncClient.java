package com.nexus.commerce.client;

import java.util.UUID;

/** Cổng đồng bộ hậu kiểm khi đơn hàng đấu giá quá hạn thanh toán 24h sang Reputation và Auction Service. */
public interface AuctionTimeoutSyncClient {

    /** Kiểm tra đã cấu hình đủ OAuth service account để gọi API nội bộ hay chưa. */
    boolean configured();

    /** Gửi yêu cầu trừ điểm uy tín (idempotent theo AUCTION + auctionId) khi winner quá hạn thanh toán 24h. */
    void applyTimeoutPenalty(UUID userId, UUID auctionId, UUID orderId, int penaltyScore, String reason);

    /** Đồng bộ trạng thái kết quả quyết toán phiên đấu giá sang DEFAULTED để nhất quán với đơn đã hủy. */
    void markAuctionDefaulted(UUID auctionId, UUID orderId);
}
