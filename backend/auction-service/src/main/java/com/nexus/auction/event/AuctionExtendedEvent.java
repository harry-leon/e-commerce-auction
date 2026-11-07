package com.nexus.auction.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền (Domain Event) phát ra khi cơ chế Anti-sniping kích hoạt gia hạn thời gian kết thúc phiên.
 *
 * Chức năng:
 * - Thông báo cho người dùng trên sàn (qua WebSocket STOMP) đồng bộ lại đồng hồ đếm ngược khi phiên được kéo dài thêm.
 *
 * Đầu vào và kết quả:
 * - auctionId: Định danh phiên đấu giá được gia hạn.
 * - newEndTime: Mốc thời gian kết thúc mới sau khi cộng thêm thời gian chống sniping.
 * - extensionCount: Số lần phiên đã được gia hạn hiện tại.
 * - message: Thông điệp thông báo lý do gia hạn (ví dụ: phát hiện lượt trả giá phút chót).
 */
public record AuctionExtendedEvent(
        UUID auctionId,
        Instant newEndTime,
        int extensionCount,
        String message
) {
}

