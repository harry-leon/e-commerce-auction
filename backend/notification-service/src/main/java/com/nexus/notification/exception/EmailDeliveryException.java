package com.nexus.notification.exception;

/**
 * Ngoại lệ phát sinh khi tiến trình gửi email qua SMTP / Mailpit gặp sự cố kỹ thuật.
 *
 * Chức năng:
 * - Đại diện cho các lỗi kết nối mạng tạm thời (connection timeout, socket closed, host unreachable).
 * - Được TransactionalEmailService bắt lại để kích hoạt cơ chế thử lại tối đa 3 lần (NOTIFICATION_RETRY_LIMIT = 3).
 *
 * Nguyên tắc cô lập lỗi (§5.2 SRS):
 * - Ngoại lệ này chỉ lưu hành nội bộ trong Notification Service, không được lan truyền ra ngoài làm hỏng luồng đặt hàng.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
