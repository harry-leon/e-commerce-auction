package com.nexus.commerce.repository;

import com.nexus.commerce.entity.OrderStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository cho OrderStatusHistory entity.
 * Ánh xạ bảng order_status_history trong PostgreSQL (schema commerce_svc).
 *
 * <p>Chức năng:
 * Ghi vết bất biến (audit log) mỗi lần chuyển trạng thái đơn hàng và truy xuất
 * dòng thời gian (timeline) tiến trình đơn hàng phục vụ API chi tiết đơn hàng (OrderDetailResponse).
 *
 * <p>Luồng xử lý:
 * Khi chuyển trạng thái đơn hàng, bản ghi mới được lưu với fromStatus, toStatus, reason và changedAt.
 * Khi xem chi tiết đơn hàng, truy vấn sắp xếp theo changedAt tăng dần để hiển thị theo thứ tự thời gian.
 */
@Repository
public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {

    /**
     * Lấy toàn bộ lịch sử chuyển trạng thái của một đơn hàng, sắp xếp theo thời gian tăng dần.
     * Đảm bảo hiển thị đúng dòng thời gian từ khi tạo đơn (PENDING_PAYMENT) cho đến trạng thái hiện tại.
     *
     * @param orderId ID của đơn hàng
     * @return danh sách các bản ghi lịch sử trạng thái theo thứ tự changedAt tăng dần (ASC)
     */
    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAsc(UUID orderId);
}
