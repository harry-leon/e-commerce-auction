package com.nexus.auction.repository;

import com.nexus.auction.entity.AuctionSettlement;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository thao tác bảng quyết toán auction_settlements.
 *
 * Chức năng:
 * - Tra cứu kết quả quyết toán theo auctionId.
 * - Kiểm tra sự tồn tại của settlement phục vụ tính lũy kế (idempotency).
 */
@Repository
public interface AuctionSettlementRepository extends JpaRepository<AuctionSettlement, UUID> {

    /**
     * Tìm bản ghi quyết toán duy nhất của một phiên đấu giá.
     */
    Optional<AuctionSettlement> findByAuctionId(UUID auctionId);

    /**
     * Kiểm tra xem phiên đấu giá đã từng được quyết toán hay chưa.
     */
    boolean existsByAuctionId(UUID auctionId);
}

