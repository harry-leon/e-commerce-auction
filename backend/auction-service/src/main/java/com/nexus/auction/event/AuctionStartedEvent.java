package com.nexus.auction.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền (Domain Event) phát ra khi một phiên đấu giá được tự động mở thành công.
 *
 * Chức năng:
 * - Thông báo cho các thành phần lắng nghe (WebSocket STOMP, Notification) rằng phiên đấu giá
 *   đã chuyển từ SCHEDULED sang ACTIVE và sẵn sàng nhận lượt đặt giá.
 *
 * Đầu vào và kết quả:
 * - auctionId: Định danh duy nhất của phiên đấu giá.
 * - startTime: Thời điểm bắt đầu chính thức của phiên.
 * - endTime: Thời điểm kết thúc dự kiến.
 * - startPrice: Mức giá khởi điểm của phiên.
 */
public record AuctionStartedEvent(
        UUID auctionId,
        Instant startTime,
        Instant endTime,
        BigDecimal startPrice
) {
}

