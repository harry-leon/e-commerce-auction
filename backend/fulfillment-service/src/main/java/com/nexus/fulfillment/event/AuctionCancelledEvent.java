package com.nexus.fulfillment.event;

import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi phiên đấu giá bị Người bán hoặc Quản trị viên hủy (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - Thông báo cho Fulfillment Service tức thời giải phóng số lượng giữ trước, hoàn trả về available_qty trong kho.
 *
 * Đầu vào và kết quả:
 * - Dữ liệu sự kiện gồm auctionId và lý do hủy phiên (reason).
 * - Listener tiếp nhận và hoàn kho, ghi sổ cái RELEASE (Idempotent).
 */
public record AuctionCancelledEvent(
        UUID auctionId,
        String reason
) {
    public static AuctionCancelledEvent of(UUID auctionId, String reason) {
        return new AuctionCancelledEvent(auctionId, reason);
    }
}

