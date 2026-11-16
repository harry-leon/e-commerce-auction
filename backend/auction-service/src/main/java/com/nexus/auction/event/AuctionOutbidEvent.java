package com.nexus.auction.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền (Domain Event) phát ra khi một lượt đặt giá (Bid) mới vượt qua mức giá của người dẫn đầu trước đó.
 *
 * Chức năng:
 * - Mang thông tin về phiên đấu giá, người bị vượt giá (outbidUserId) và mức giá mới.
 * - Được phát đi sau khi giao dịch cơ sở dữ liệu commit thành công.
 *
 * Đầu vào và kết quả:
 * - Chứa auctionId, auctionTitle, outbidUserId, newAmount, occurredAt.
 */
public record AuctionOutbidEvent(
        UUID auctionId,
        String auctionTitle,
        UUID outbidUserId,
        BigDecimal newAmount,
        Instant occurredAt
) {
}
