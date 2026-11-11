package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.InventoryReservation;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
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
 * Repository quản lý các phiếu giữ hàng tồn kho (Inventory Reservation).
 *
 * Chức năng:
 * - Tra cứu phiếu giữ kho theo ID, theo mã đơn hàng tham chiếu (ref_id).
 * - Quét tìm các phiếu PENDING đã quá hạn (expires_at < current_time) để phục vụ auto-release.
 * - Khóa dòng phiếu giữ hàng khi thực hiện hoàn trả hoặc xác nhận xuất kho.
 */
@Repository
public interface InventoryReservationRepository extends JpaRepository<InventoryReservation, UUID> {

    /**
     * Tra cứu danh sách phiếu giữ hàng gắn với loại tham chiếu và mã tham chiếu (ORDER/AUCTION và order_id/auction_id).
     */
    List<InventoryReservation> findByRefTypeAndRefId(ReservationRefType refType, UUID refId);

    /**
     * Tra cứu danh sách phiếu giữ kho theo trạng thái và thời gian hết hạn trước một mốc (dùng cho auto-release scheduler).
     */
    List<InventoryReservation> findByStatusAndExpiresAtBefore(ReservationStatus status, Instant cutoffTime);

    /**
     * Chỉ lấy ID ứng viên quá hạn trước khi khóa; tránh nạp sớm entity PENDING vào JPA cache rồi nhả nhầm phiếu vừa CONFIRMED.
     */
    @Query("SELECT r.id FROM InventoryReservation r WHERE r.status = :status AND r.expiresAt < :cutoff ORDER BY r.id")
    List<UUID> findExpiredIds(@Param("status") ReservationStatus status, @Param("cutoff") Instant cutoff);

    /**
     * Tính tổng số lượng đang bị giữ (PENDING) theo inventory_id phục vụ đối soát khớp sổ cái và tồn kho.
     */
    @Query("SELECT COALESCE(SUM(r.quantity), 0) FROM InventoryReservation r WHERE r.inventory.id = :inventoryId AND r.status = com.nexus.fulfillment.enums.ReservationStatus.PENDING")
    Integer sumPendingQuantityByInventoryId(@Param("inventoryId") UUID inventoryId);

    /**
     * Tra cứu và khóa dòng một phiếu giữ hàng theo ID để tránh việc nhiều tiến trình cùng release hoặc confirm một lúc.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.id = :id")
    Optional<InventoryReservation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Tra cứu và khóa danh sách phiếu giữ hàng theo mã tham chiếu và trạng thái.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.refType = :refType AND r.refId = :refId AND r.status = :status")
    List<InventoryReservation> findByRefTypeAndRefIdAndStatusForUpdate(
            @Param("refType") ReservationRefType refType,
            @Param("refId") UUID refId,
            @Param("status") ReservationStatus status
    );

    /**
     * Tra cứu và khóa toàn bộ danh sách phiếu giữ hàng của một mã tham chiếu (ORDER/AUCTION).
     * Sắp xếp theo ID tăng dần để đảm bảo thứ tự khóa nhất quán, chống Deadlock.
     * Dùng cho kiểm tra Idempotency khi commit hoặc release toàn bộ đơn hàng.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM InventoryReservation r WHERE r.refType = :refType AND r.refId = :refId ORDER BY r.id ASC")
    List<InventoryReservation> findByRefTypeAndRefIdForUpdate(
            @Param("refType") ReservationRefType refType,
            @Param("refId") UUID refId
    );
}
