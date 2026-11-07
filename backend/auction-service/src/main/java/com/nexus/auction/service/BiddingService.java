package com.nexus.auction.service;

import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện service xử lý nghiệp vụ đặt giá (Bidding) và truy vấn lịch sử đặt giá (HG-S2-3).
 *
 * Chức năng:
 * - Tiếp nhận và xử lý đặt giá thầu trong thời gian thực (Atomic & Idempotent).
 * - Kiểm soát xung đột đồng thời (Concurrency Control qua Optimistic Locking / DB Versioning).
 * - Tự động kích hoạt cơ chế gia hạn chống bắn tỉa phút chót (Anti-sniping protection).
 * - Truy vấn lịch sử đặt giá của phiên đấu giá có phân trang.
 */
public interface BiddingService {

    /**
     * Đặt giá thầu cho một phiên đấu giá đang mở (ACTIVE).
     *
     * @param auctionId Mã phiên đấu giá
     * @param bidderId  Mã người mua tham gia đặt giá (lấy từ Security Context)
     * @param request   Dữ liệu mức giá và idempotencyKey
     * @return PlaceBidResult Kết quả đặt giá và thông tin phiên đấu giá cập nhật
     */
    PlaceBidResult placeBid(UUID auctionId, UUID bidderId, PlaceBidRequest request);

    /**
     * Lấy lịch sử các lượt đặt giá của một phiên đấu giá.
     *
     * @param auctionId Mã phiên đấu giá
     * @param pageable  Thông tin phân trang và sắp xếp
     * @return Trang danh sách các lượt đặt giá
     */
    Page<BidResponse> getBidHistory(UUID auctionId, Pageable pageable);
}

