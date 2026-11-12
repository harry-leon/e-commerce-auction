package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.ShipmentStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository truy vấn lịch sử chuyển trạng thái của từng vận đơn theo thời gian giảm dần.
 */
public interface ShipmentStatusHistoryRepository extends JpaRepository<ShipmentStatusHistory, UUID> {

    List<ShipmentStatusHistory> findByShipmentIdOrderByOccurredAtDesc(UUID shipmentId);
}
