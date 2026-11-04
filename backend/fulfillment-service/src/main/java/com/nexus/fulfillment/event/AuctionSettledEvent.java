package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi phiên đấu giá chốt thành công có người thắng cuộc và đã khởi tạo Đơn hàng (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - Kích hoạt chuyển tiếp trạng thái giữ hàng từ phiên đấu giá sang Đơn hàng của người thắng cuộc (Transition to Order).
 * - Đảm bảo không xảy ra hiện tượng Reserve kép (Double Reservation) và giữ liên tục trạng thái khóa hàng.
 *
 * Đầu vào và kết quả:
 * - Dữ liệu sự kiện gồm auctionId, orderId, winnerId và paymentDeadline.
 * - Listener tiếp nhận và chuyển đổi mã tham chiếu từ auction_id sang order_id trên phiếu giữ hàng.
 */
public record AuctionSettledEvent(
        UUID auctionId,
        UUID orderId,
        UUID winnerId,
        Instant paymentDeadline
) {
    public static AuctionSettledEvent of(UUID auctionId, UUID orderId, UUID winnerId, Instant paymentDeadline) {
        return new AuctionSettledEvent(auctionId, orderId, winnerId, paymentDeadline);
    }
}

