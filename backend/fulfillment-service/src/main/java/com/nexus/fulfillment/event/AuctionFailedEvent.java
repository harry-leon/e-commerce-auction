package com.nexus.fulfillment.event;

import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi phiên đấu giá kết thúc mà không có người đặt giá (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - Thông báo cho Fulfillment Service tức thời giải phóng số lượng giữ trước, hoàn trả về available_qty trong kho.
 *
 * Đầu vào và kết quả:
 * - Dữ liệu sự kiện gồm auctionId và lý do thất bại (reason).
 * - Listener tiếp nhận và hoàn kho, ghi sổ cái RELEASE (Idempotent).
 */
public record AuctionFailedEvent(
        UUID auctionId,
        String reason
) {
    public static AuctionFailedEvent of(UUID auctionId, String reason) {
        return new AuctionFailedEvent(auctionId, reason);
    }
}

