package com.nexus.auction.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload yêu cầu chuyển tiếp phiếu giữ kho từ AUCTION sang ORDER gửi sang Fulfillment Service (SRS §3.6.1).
 *
 * Chức năng:
 * - Chuyển đổi mã tham chiếu refType = AUCTION, refId = auctionId sang refType = ORDER, refId = orderId.
 * - Thiết lập thời hạn thanh toán đơn hàng mới (mặc định 24 giờ).
 *
 * Đầu vào và kết quả:
 * - Đầu vào: orderId (mã đơn hàng Commerce), paymentDeadlineMinutes, paymentDeadline.
 * - Kết quả: Fulfillment Service cập nhật phiếu giữ kho mà không nhả hàng ra thị trường (tránh Reserve kép).
 */
public record TransitionAuctionReservationPayload(
        UUID orderId,
        Integer paymentDeadlineMinutes,
        Instant paymentDeadline
) {
}
