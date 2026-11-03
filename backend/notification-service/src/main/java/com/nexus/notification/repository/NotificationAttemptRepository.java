package com.nexus.notification.repository;

import com.nexus.notification.entity.NotificationAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository truy xuất lịch sử các lần thử gửi thông báo từ bảng notification_attempts (ERD Sprint 1).
 *
 * Chức năng:
 * - Lưu bản ghi chi tiết mỗi lần thử gửi qua email (attempt_no từ 1 đến 3).
 * - Lấy toàn bộ lịch sử thử lại của một thông báo theo thứ tự tăng dần của số lần thử.
 */
@Repository
public interface NotificationAttemptRepository extends JpaRepository<NotificationAttempt, UUID> {

    /**
     * Lấy danh sách các lần thử của một thông báo theo thứ tự lần thử tăng dần.
     */
    List<NotificationAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}
