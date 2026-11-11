package com.nexus.auction.repository;

import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.enums.SettlementResult;
import java.util.List;
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
 * - Quét danh sách các phiên quyết toán thành công nhưng chưa bàn giao tạo đơn sang Commerce (orderId IS NULL).
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

    /**
     * Tìm danh sách các bản ghi quyết toán theo kết quả và chưa được liên kết với đơn hàng (orderId IS NULL).
     * Phục vụ cho background worker quét và thử lại việc bàn giao tạo đơn hàng sang Commerce Service (Resilient Handover).
     *
     * @param result Kết quả quyết toán (SUCCESS)
     * @return Danh sách các bản ghi quyết toán thỏa mãn điều kiện
     */
    List<AuctionSettlement> findByResultAndOrderIdIsNull(SettlementResult result);
}

