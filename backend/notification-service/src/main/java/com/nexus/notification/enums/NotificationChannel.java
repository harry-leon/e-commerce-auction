package com.nexus.notification.enums;

/**
 * Kênh gửi thông báo tương ứng với ràng buộc chk_notification_channel trong CSDL.
 *
 * Chức năng:
 * - EMAIL: Kênh gửi email giao dịch bắt buộc (SRS §3.2.3).
 * - PUSH: Thông báo đẩy tới thiết bị di động.
 * - IN_APP: Thông báo hiển thị nội bộ trong giao diện ứng dụng.
 * - SMS: Tin nhắn SMS tới số điện thoại.
 */
public enum NotificationChannel {
    EMAIL,
    PUSH,
    IN_APP,
    SMS
}
