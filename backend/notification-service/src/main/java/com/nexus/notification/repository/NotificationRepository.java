package com.nexus.notification.repository;

import com.nexus.notification.entity.Notification;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất dữ liệu thông báo từ bảng notifications (ERD Sprint 1).
 *
 * Chức năng:
 * - Lưu mới và cập nhật trạng thái thông báo (PENDING -> SENT / FAILED).
 * - Tra cứu theo event_id để hỗ trợ kiểm tra tính idempotent (ngăn gửi trùng email).
 * - Lấy danh sách lịch sử thông báo của một người dùng theo thời gian tạo mới nhất.
 */
@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Tra cứu thông báo theo mã định danh sự kiện để đảm bảo tính Idempotent.
     */
    Optional<Notification> findByEventId(String eventId);

    /**
     * Lấy toàn bộ lịch sử thông báo của người dùng sắp xếp giảm dần theo thời gian tạo.
     */
    List<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
