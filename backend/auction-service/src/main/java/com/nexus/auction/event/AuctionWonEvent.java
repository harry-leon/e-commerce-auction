package com.nexus.auction.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi một phiên đấu giá kết thúc thành công và xác định được người thắng cuộc (SRS §2.8 & §3.5.3).
 *
 * Chức năng:
 * - Đại diện cho kết quả chiến thắng hợp lệ của phiên đấu giá (Auction Won).
 * - Cung cấp dữ liệu tin cậy (Settlement Data) bao gồm auctionId, productId, skuId, winnerId (Buyer), sellerId, finalPrice
 *   để bàn giao sang Commerce Service khởi tạo đơn hàng chính thức.
 *
 * Đầu vào và kết quả:
 * - Chứa đầy đủ thông tin định danh phiên, sản phẩm, các bên tham gia, mức giá chốt và hạn chót thanh toán (+24h).
 * - Được lắng nghe bởi các tiến trình nội bộ hoặc tích hợp downstream.
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5.3: Auction Service không tự tạo đơn trong CSDL của mình mà bàn giao kết quả tin cậy sang Commerce Service.
 */
public record AuctionWonEvent(
        UUID auctionId,
        UUID productId,
        UUID skuId,
        UUID winnerId,
        UUID sellerId,
        BigDecimal finalPrice,
        Instant paymentDeadline,
        Instant occurredAt
) {
    public static AuctionWonEvent of(
            UUID auctionId,
            UUID productId,
            UUID skuId,
            UUID winnerId,
            UUID sellerId,
            BigDecimal finalPrice,
            Instant paymentDeadline
    ) {
        return new AuctionWonEvent(
                auctionId,
                productId,
                skuId,
                winnerId,
                sellerId,
                finalPrice,
                paymentDeadline,
                Instant.now()
        );
    }
}

