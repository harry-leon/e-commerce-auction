package com.nexus.auction.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload gửi sang Commerce Service để yêu cầu khởi tạo đơn hàng đấu giá (SRS §3.5.3).
 *
 * Chức năng:
 * - Đóng gói dữ liệu kết quả quyết toán được Auction Service truyền qua HTTP RestClient.
 *
 * Đầu vào và kết quả:
 * - Bao gồm: auctionId, winnerId, sellerId, productId, skuId, finalPrice, paymentDeadline, productName, skuCode.
 */
public record CreateAuctionOrderPayload(
        UUID auctionId,
        UUID winnerId,
        UUID sellerId,
        UUID productId,
        UUID skuId,
        BigDecimal finalPrice,
        Instant paymentDeadline,
        String productName,
        String skuCode
) {
}

