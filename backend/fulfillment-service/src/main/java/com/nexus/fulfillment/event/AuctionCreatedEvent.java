package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi Người bán tạo phiên đấu giá thành công (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - Thông báo cho Fulfillment Service biết để kiểm tra và giữ kho chuyên biệt cho Auction (ref_type = AUCTION).
 *
 * Đầu vào và kết quả:
 * - Dữ liệu sự kiện gồm auctionId, skuId, warehouseId, startTime, endTime và quantity.
 * - Listener tiếp nhận và tạo phiếu giữ kho PENDING có TTL bao quát toàn bộ phiên đấu giá.
 */
public record AuctionCreatedEvent(
        UUID auctionId,
        UUID skuId,
        UUID warehouseId,
        Instant startTime,
        Instant endTime,
        int quantity
) {
    public static AuctionCreatedEvent of(
            UUID auctionId,
            UUID skuId,
            UUID warehouseId,
            Instant startTime,
            Instant endTime,
            int quantity
    ) {
        return new AuctionCreatedEvent(auctionId, skuId, warehouseId, startTime, endTime, quantity);
    }
}

