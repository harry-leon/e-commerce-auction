package com.nexus.auction.repository;

import com.nexus.auction.entity.Bid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu lịch sử đặt giá bảng bids.
 *
 * Chức năng:
 * - Tìm kiếm lượt đặt giá hợp lệ cao nhất của phiên đấu giá để xác định người thắng cuộc.
 * - Sắp xếp theo amount giảm dần, đặt giá trước (placedAt tăng dần) ưu tiên thắng giải hòa.
 * - Kiểm tra trùng lặp theo khóa chống trùng lặp (idempotency_key).
 * - Đếm và phân trang lịch sử đặt giá theo phiên đấu giá.
 */
@Repository
public interface BidRepository extends JpaRepository<Bid, UUID> {

    /**
     * Lấy lượt đặt giá cao nhất của phiên. Nếu cùng giá cao nhất, ưu tiên lượt đặt trước.
     */
    Optional<Bid> findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(UUID auctionId);

    /**
     * Lấy toàn bộ danh sách đặt giá của phiên theo thứ tự giá giảm dần.
     */
    List<Bid> findByAuctionIdOrderByAmountDesc(UUID auctionId);

    /**
     * Tra cứu lượt đặt giá theo khóa chống trùng lặp (Idempotency Key).
     */
    Optional<Bid> findByIdempotencyKey(String idempotencyKey);

    /**
     * Đếm tổng số lượt đặt giá của một phiên đấu giá.
     */
    long countByAuctionId(UUID auctionId);

    /**
     * Lấy danh sách lịch sử đặt giá của một phiên đấu giá có phân trang.
     */
    Page<Bid> findByAuctionId(UUID auctionId, Pageable pageable);
}
