package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phản hồi chi tiết đơn hàng tạo từ phiên đấu giá thành công (SRS §3.5.3 & §3.4.3).
 *
 * Chức năng:
 * - Cung cấp thông tin đơn hàng đấu giá chính thức đã được ghi nhận trong cơ sở dữ liệu Commerce Service.
 * - Trả về cho Auction Service để hoàn tất liên kết order_id vào bản ghi auction_settlements.
 *
 * Đầu vào và kết quả:
 * - Trả về: orderId, orderNo, auctionId, buyerId (winner), sellerId, status, total, paymentDeadline, createdAt.
 *
 * Quy tắc và lý do:
 * - Sử dụng record bất biến theo phong cách thiết kế hiện đại của hệ thống.
 * - paymentDeadline được thiết lập theo hạn thanh toán 24 giờ của phiên đấu giá.
 */
public record AuctionOrderResponse(
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
     * Constructor tương thích với OrderStatus enum và tự gán createdAt mặc định.
     */
    public AuctionOrderResponse(
            UUID orderId,
            String orderNo,
            UUID auctionId,
            UUID buyerId,
            UUID sellerId,
            com.nexus.commerce.enums.OrderStatus status,
            BigDecimal total,
            Instant paymentDeadline) {
        this(orderId, orderNo, auctionId, buyerId, sellerId, status != null ? status.name() : null, total, paymentDeadline, Instant.now());
    }
}

