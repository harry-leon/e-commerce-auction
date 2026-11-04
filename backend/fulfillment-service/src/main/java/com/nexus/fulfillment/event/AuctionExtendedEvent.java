package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi phiên đấu giá được gia hạn do Anti-sniping (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - Kéo dài thời hạn sống (expires_at) của phiếu giữ hàng tồn kho trong Fulfillment Service,
 *   ngăn chặn việc kho bị giải phóng nhầm khi phiên vẫn đang mở.
 *
 * Đầu vào và kết quả:
 * - Dữ liệu sự kiện gồm auctionId, extensionMinutes, newEndTime.
 * - Listener tiếp nhận và kéo dài expires_at của phiếu giữ kho tương ứng.
 */
public record AuctionExtendedEvent(
        UUID auctionId,
        Integer extensionMinutes,
        Instant newEndTime
) {
    public static AuctionExtendedEvent of(UUID auctionId, Integer extensionMinutes, Instant newEndTime) {
        return new AuctionExtendedEvent(auctionId, extensionMinutes, newEndTime);
    }
}

