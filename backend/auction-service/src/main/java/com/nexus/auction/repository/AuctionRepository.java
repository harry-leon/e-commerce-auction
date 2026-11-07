package com.nexus.auction.repository;

import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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
 * - Hỗ trợ kiểm tra giới hạn phiên active của Seller và kiểm tra chống trùng phiên active cho cùng một sản phẩm/SKU.
 * - Hỗ trợ tìm kiếm, lọc động và phân trang bằng JpaSpecificationExecutor.
 */
@Repository
public interface AuctionRepository extends JpaRepository<Auction, UUID>, JpaSpecificationExecutor<Auction> {

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

    /**
     * Tìm danh sách các phiên đấu giá theo trạng thái có thời gian bắt đầu trước hoặc bằng mốc cutoff.
     * Phục vụ scheduler tự động mở phiên khi startTime <= now.
     */
    List<Auction> findByStatusAndStartTimeLessThanEqual(AuctionStatus status, Instant cutoffTime);

    /**
     * Đếm số lượng phiên đấu giá của người bán đang ở các trạng thái chỉ định (ví dụ SCHEDULED, ACTIVE).
     */
    long countBySellerIdAndStatusIn(UUID sellerId, Collection<AuctionStatus> statuses);

    /**
     * Kiểm tra xem sản phẩm đã có phiên đấu giá nào đang mở/hoạt động hay chưa.
     */
    boolean existsByProductIdAndStatusIn(UUID productId, Collection<AuctionStatus> statuses);

    /**
     * Kiểm tra xem biến thể SKU đã có phiên đấu giá nào đang mở/hoạt động hay chưa.
     */
    boolean existsBySkuIdAndStatusIn(UUID skuId, Collection<AuctionStatus> statuses);
}
