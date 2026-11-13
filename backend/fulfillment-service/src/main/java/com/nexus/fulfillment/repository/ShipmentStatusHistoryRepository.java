package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất lịch sử thay đổi trạng thái vận đơn (ShipmentStatusHistory).
 *
 * Chức năng:
 * - Lưu và đọc các sự kiện thay đổi trạng thái vận đơn kèm mốc thời gian và mã sự kiện từ carrier.
 * - Kiểm tra tính idempotent theo eventId để bỏ qua webhook/callback hoặc polling trùng lặp (KH-S3-2).
 */
@Repository
public interface ShipmentStatusHistoryRepository extends JpaRepository<ShipmentStatusHistory, UUID> {

    /**
     * Lấy toàn bộ lịch sử trạng thái của một vận đơn, sắp xếp mới nhất lên đầu.
     */
    List<ShipmentStatusHistory> findByShipmentIdOrderByOccurredAtDesc(UUID shipmentId);

    /**
     * Kiểm tra xem mã sự kiện / transaction ID từ carrier callback đã từng được xử lý hay chưa (Idempotency check).
     */
    boolean existsByEventId(String eventId);

    /**
     * Tìm bản ghi lịch sử trạng thái theo eventId từ carrier callback.
     */
    Optional<ShipmentStatusHistory> findByEventId(String eventId);
}
