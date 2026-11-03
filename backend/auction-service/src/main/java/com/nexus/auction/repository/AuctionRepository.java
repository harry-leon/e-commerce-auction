package com.nexus.auction.repository;

import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất và thao tác dữ liệu bảng auctions.
 *
 * Chức năng:
 * - Cung cấp các thao tác CRUD cơ bản cho entity Auction.
 * - Hỗ trợ tìm kiếm với khóa bi quan (PESSIMISTIC_WRITE) để khóa phiên khi chốt kết quả hoặc đặt giá.
 * - Tìm kiếm các phiên đang ACTIVE và đã hết hạn (endTime <= cutoffTime) để phục vụ scheduler quyết toán.
 */
@Repository
public interface AuctionRepository extends JpaRepository<Auction, UUID> {

    /**
     * Khóa bi quan bản ghi Auction để cập nhật trạng thái kết thúc/quyết toán mà không bị race condition.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Auction a WHERE a.id = :id")
    Optional<Auction> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Tìm danh sách các phiên đấu giá theo trạng thái có thời gian kết thúc trước hoặc bằng mốc cutoff.
     */
    List<Auction> findByStatusAndEndTimeLessThanEqual(AuctionStatus status, Instant cutoffTime);
}

