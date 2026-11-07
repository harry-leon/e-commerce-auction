package com.nexus.auction.event;

import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.SettlementResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền (Domain Event) phát ra khi một phiên đấu giá kết thúc và hoàn tất bàn giao quyết toán (Settlement Handoff).
 *
 * Chức năng:
 * - Đại diện cho mốc chuyển giao kết quả phiên đấu giá (Settlement Handoff):
 *   + Nếu SUCCESS: Cung cấp thông tin người chiến thắng (winnerId) và giá chốt (finalPrice) để các phân hệ
 *     downstream (như Commerce Service tạo đơn hàng đấu giá, Notification Service gửi thư chúc mừng).
 *   + Nếu FAILED: Cung cấp tín hiệu để hoàn trả SKU tồn kho (AUCTION_RESERVED -> ACTIVE).
 *
 * Đầu vào và kết quả:
 * - auctionId: Định danh phiên đấu giá vừa đóng.
 * - status: Trạng thái cuối của phiên (ENDED hoặc SETTLED).
 * - settlementResult: Kết quả quyết toán (SUCCESS hoặc FAILED).
 * - winnerId: Mã người thắng cuộc (hoặc null nếu không có ai trả giá).
 * - finalPrice: Giá thắng cuộc cuối cùng (hoặc null nếu phiên thất bại).
 * - closedAt: Thời điểm đóng phiên và quyết toán.
 */
public record AuctionClosedEvent(
        UUID auctionId,
        AuctionStatus status,
        SettlementResult settlementResult,
        UUID winnerId,
        BigDecimal finalPrice,
        Instant closedAt
) {
}

