package com.nexus.auction.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh sau khi đơn hàng đã được tạo thành công trong hệ thống Commerce (SRS §2.8 & §3.5.3).
 *
 * Chức năng:
 * - Xác nhận việc bàn giao kết quả chốt phiên (Settlement Handover) đã hoàn tất thành công.
 * - Cung cấp liên kết chính thức giữa auctionId và orderId của đơn hàng đã được tạo.
 * - Thông báo cho Fulfillment Service thực hiện chuyển tiếp phiếu giữ hàng (Transition to Order) mà không bị Reserve kép.
 *
 * Đầu vào và kết quả:
 * - auctionId: Định danh phiên đấu giá vừa quyết toán.
 * - orderId: Mã đơn hàng vừa được Commerce Service tạo thành công.
 * - winnerId: Mã người mua chiến thắng.
 * - paymentDeadline: Thời hạn thanh toán (24 giờ kể từ thời điểm chốt phiên).
 * - occurredAt: Thời điểm sự kiện phát sinh.
 *
 * Quy tắc và lý do:
 * - Chỉ phát sinh sau khi đơn hàng đã được ghi nhận chắc chắn vào cơ sở dữ liệu Commerce Service.
 */
public record AuctionSettledEvent(
        UUID auctionId,
        UUID orderId,
        UUID winnerId,
        Instant paymentDeadline,
        Instant occurredAt
) {
    public static AuctionSettledEvent of(
            UUID auctionId,
            UUID orderId,
            UUID winnerId,
            Instant paymentDeadline
    ) {
        return new AuctionSettledEvent(auctionId, orderId, winnerId, paymentDeadline, Instant.now());
    }
}

