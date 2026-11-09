package com.nexus.auction.client.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Kết quả khởi tạo đơn hàng nhận được từ Commerce Service (SRS §3.5.3).
 *
 * Chức năng:
 * - Đại diện cho đơn hàng đấu giá chính thức đã được ghi nhận trong Commerce Service.
 * - Chứa orderId dùng để liên kết vào bản ghi auction_settlements.
 */
public record AuctionOrderResult(
        UUID orderId,
        String orderNo,
        UUID auctionId,
        UUID buyerId,
        UUID sellerId,
        String status,
        BigDecimal total,
        Instant paymentDeadline,
        Instant createdAt
) {
    /**
     * Constructor thuận tiện cho test và caller với thời gian createdAt mặc định.
     */
    public AuctionOrderResult(
            UUID orderId,
            String orderNo,
            UUID auctionId,
            UUID buyerId,
            UUID sellerId,
            String status,
            BigDecimal total,
            Instant paymentDeadline) {
        this(orderId, orderNo, auctionId, buyerId, sellerId, status, total, paymentDeadline, Instant.now());
    }
}
