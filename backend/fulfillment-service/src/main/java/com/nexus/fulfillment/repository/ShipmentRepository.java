package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.Shipment;
import com.nexus.fulfillment.enums.ShipmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository quản lý vận đơn (Shipment) theo đơn hàng, mã vận đơn, khóa idempotency và trạng thái.
 *
 * Chức năng:
 * - Tìm kiếm vận đơn theo id, orderId, trackingNo và idempotencyKey (nạp sẵn warehouse và carrier qua EntityGraph).
 * - Tìm kiếm danh sách vận đơn đang hoạt động (active statuses) phục vụ job polling đồng bộ định kỳ (KH-S3-2).
 */
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    Optional<Shipment> findWithDetailsById(UUID id);

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    Optional<Shipment> findByOrderId(UUID orderId);

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    Optional<Shipment> findByTrackingNo(String trackingNo);

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    Optional<Shipment> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    Page<Shipment> findByStatus(ShipmentStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"warehouse", "carrier"})
    List<Shipment> findByStatusIn(Collection<ShipmentStatus> statuses);
}
