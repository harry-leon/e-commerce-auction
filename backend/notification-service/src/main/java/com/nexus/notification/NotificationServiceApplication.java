package com.nexus.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Ứng dụng Notification Service chịu trách nhiệm xử lý thông báo và email giao dịch (SRS §3.2.3).
 *
 * Chức năng:
 * - Tiếp nhận các sự kiện miền từ các service: OrderCreated, OrderPaid, OrderPaymentFailed.
 * - Gửi email xác nhận giao dịch bất đồng bộ qua SMTP / Mailpit (port 1025) theo SRS §5.2.
 * - Tự động thử lại tối đa 3 lần (NOTIFICATION_RETRY_LIMIT = 3) khi gặp lỗi mạng tạm thời.
 * - Lưu vết lịch sử thông báo và các lần thử gửi (notifications, notification_attempts).
 *
 * Quy tắc:
 * - Bật @EnableAsync để tách biệt hoàn toàn luồng gửi email khỏi luồng thanh toán / đặt hàng của người dùng.
 */
@SpringBootApplication
@EnableAsync
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
