package com.nexus.auction.service;

import com.nexus.auction.dto.response.AuctionSettlementResponse;
import java.util.UUID;

/**
 * Interface nghiệp vụ tích hợp quyết toán và bàn giao khởi tạo đơn hàng sang Commerce Service (SRS §3.5.3 [KH-S2-2]).
 *
 * Chức năng:
 * - Tiếp nhận dữ liệu tin cậy từ phiên đấu giá thành công (AuctionWon).
 * - Xác thực tính toàn vẹn của kết quả trước khi tiến hành bàn giao sang Commerce Service.
 * - Kích hoạt Commerce Service tạo đơn hàng cho người thắng (sourceType = AUCTION, hạn thanh toán 24h).
 * - Đảm bảo tính lũy nghiệm (Idempotency) tuyệt đối và cơ chế retry an toàn.
 * - Phát sinh sự kiện xác nhận AuctionSettled sau khi đơn hàng được tạo thành công.
 */
public interface AuctionOrderHandoverService {

    /**
     * Bàn giao kết quả phiên đấu giá thành công sang Commerce Service để tạo đơn hàng chính thức.
     *
     * @param auctionId định danh phiên đấu giá đã quyết toán thành công
     * @return kết quả quyết toán đã cập nhật mã đơn hàng (orderId)
     */
    AuctionSettlementResponse handoverAuctionOrder(UUID auctionId);
}

