package com.nexus.auction.service;

import java.util.UUID;

/**
 * Interface điều phối vòng đời của phiên đấu giá (Auction Lifecycle Management - HG-S2-4).
 *
 * Chức năng:
 * - Tự động kích hoạt (mở) các phiên đấu giá đến giờ bắt đầu (SCHEDULED -> ACTIVE).
 * - Tự động đóng các phiên đấu giá đã đến giờ kết thúc (ACTIVE -> ENDED / SETTLED) và bàn giao quyết toán (Settlement Handoff).
 * - Kiểm tra tính hợp lệ của trạng thái trước khi thực hiện chuyển đổi (Status Validation).
 * - Ngăn chặn thực thi trùng lặp (Duplicate Lifecycle Execution Prevention) bằng cơ chế khóa bi quan / kiểm tra trạng thái nguyên tử.
 * - Xử lý an toàn khi lỗi xảy ra và hỗ trợ cơ chế phục hồi/thử lại an toàn (Safe Retry) qua scheduler.
 */
public interface AuctionLifecycleService {

    /**
     * Mở một phiên đấu giá cụ thể từ SCHEDULED sang ACTIVE.
     *
     * @param auctionId định danh phiên đấu giá
     * @return true nếu phiên được mở thành công, false nếu bị bỏ qua do không đủ điều kiện (đã mở hoặc sai trạng thái)
     */
    boolean openAuction(UUID auctionId);

    /**
     * Quét và tự động mở tất cả các phiên đấu giá đang ở SCHEDULED mà startTime <= now.
     *
     * @return số lượng phiên đã được mở thành công
     */
    int openScheduledAuctions();

    /**
     * Đóng một phiên đấu giá cụ thể đã đến hạn kết thúc và kích hoạt bàn giao quyết toán (Settlement Handoff).
     *
     * @param auctionId định danh phiên đấu giá
     */
    void closeAuction(UUID auctionId);

    /**
     * Quét và tự động đóng tất cả các phiên đấu giá đang ACTIVE mà endTime <= now,
     * đồng thời bàn giao quyết toán xác định người chiến thắng.
     *
     * @return số lượng phiên đã được đóng và quyết toán thành công
     */
    int closeExpiredAuctions();
}

