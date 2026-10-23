package com.nexus.commerce.repository;

import com.nexus.commerce.entity.OrderStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho entity OrderStatusHistory.
 *
 * <p>Cung cấp truy vấn lịch sử biến đổi trạng thái phục vụ audit trail và hiển thị tiến trình đơn hàng.
 */
@Repository
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    /**
     * Lấy toàn bộ lịch sử trạng thái của một đơn hàng, sắp xếp theo thời gian tăng dần (timeline).
     *
     * @param orderId ID định danh của đơn hàng
     * @return Danh sách lịch sử trạng thái theo trình tự thời gian
     */
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
