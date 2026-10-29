package com.nexus.notification.enums;

/**
 * Trạng thái xử lý của thông báo tương ứng với ràng buộc chk_notification_status trong CSDL.
 *
 * Chức năng:
 * - PENDING: Thông báo đang chờ xử lý hoặc đang trong tiến trình gửi/thử lại.
 * - SENT: Thông báo đã được gửi thành công qua kênh tương ứng.
 * - FAILED: Quá trình gửi thất bại sau khi đã vượt quá giới hạn số lần thử lại (NOTIFICATION_RETRY_LIMIT = 3).
 * - READ: Người dùng đã mở đọc thông báo (áp dụng cho IN_APP / PUSH).
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED,
    READ
}
