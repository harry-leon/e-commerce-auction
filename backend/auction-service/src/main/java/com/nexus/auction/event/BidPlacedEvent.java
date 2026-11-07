package com.nexus.auction.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền (Domain Event) phát ra khi một lượt đặt giá (Bid) hợp lệ được ghi nhận thành công.
 *
 * Chức năng:
 * - Mang thông tin snapshot của lượt đặt giá và trạng thái cập nhật của phiên đấu giá.
 * - Được phát đi sau khi giao dịch cơ sở dữ liệu đã commit thành công (qua TransactionSynchronization
 *   hoặc @TransactionalEventListener(phase = AFTER_COMMIT)).
 *
 * Đầu vào và kết quả:
 * - Chứa auctionId, bidId, bidderId, amount, currentPrice, bidCount, placedAt, endTime, extended.
 */
public record BidPlacedEvent(
        UUID auctionId,
        UUID bidId,
        UUID bidderId,
        BigDecimal amount,
        BigDecimal currentPrice,
        long bidCount,
        Instant placedAt,
        Instant endTime,
        boolean extended
) {
}

