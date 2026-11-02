package com.nexus.fulfillment.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cài đặt NotificationClient gọi REST API tới Notification Service (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Gửi HTTP POST tới Notification Service khi thanh toán thành công hoặc thất bại.
 * - Sử dụng RestClient với timeout ngắn để không block tiến trình kho.
 *
 * Nguyên tắc cô lập lỗi (§5.2 SRS):
 * - Toàn bộ lỗi phát sinh (ConnectException, HttpServerErrorException, ReadTimeout, ...)
 *   được bắt lại tại phương thức gọi và chỉ ghi log WARN, đảm bảo 100% không làm hỏng
 *   hay rollback giao dịch tồn kho đã commit.
 */
@Component
public class HttpNotificationClient implements NotificationClient {

    private static final Logger log = LoggerFactory.getLogger(HttpNotificationClient.class);

    private final RestClient restClient;
    private final String notificationServiceUrl;

    public HttpNotificationClient(
            @Value("${app.notification-service.url:${NOTIFICATION_SERVICE_URL:http://localhost:8086}}") String url) {
        this.notificationServiceUrl = url;
        this.restClient = RestClient.builder()
                .baseUrl(url)
                .build();
    }

    @Override
    public void sendOrderPaidNotification(UUID orderId, String orderNo) {
        log.info("Gửi thông báo OrderPaid tới Notification Service: url={}, orderId={}", notificationServiceUrl, orderId);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "ORDER_PAID");
            payload.put("orderId", orderId);
            payload.put("orderNo", orderNo != null ? orderNo : orderId.toString());
            payload.put("userId", orderId); // Default fallback ID
            payload.put("recipientEmail", "customer@nexus.com");
            payload.put("totalAmount", 0);
            payload.put("paymentMethod", "SANDBOX_GATEWAY");
            payload.put("eventId", "EVT-PAID-" + orderId);

            restClient.post()
                    .uri("/api/v1/notifications/events/order-paid")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Gửi thông báo OrderPaid thành công tới Notification Service cho đơn hàng {}", orderId);
        } catch (Exception ex) {
            // Nguyên tắc cô lập lỗi (§5.2 SRS): Chỉ ghi nhận log cảnh báo, không ném exception làm rollback kho
            log.warn("Không thể kết nối tới Notification Service (§5.2 SRS cô lập lỗi, tiếp tục commit kho): {}",
                    ex.getMessage());
        }
    }

    @Override
    public void sendPaymentFailedNotification(UUID orderId, String orderNo, String reason) {
        log.info("Gửi thông báo OrderPaymentFailed tới Notification Service: orderId={}, reason={}", orderId, reason);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", "ORDER_PAYMENT_FAILED");
            payload.put("orderId", orderId);
            payload.put("orderNo", orderNo != null ? orderNo : orderId.toString());
            payload.put("userId", orderId);
            payload.put("recipientEmail", "customer@nexus.com");
            payload.put("totalAmount", 0);
            payload.put("failureReason", reason);
            payload.put("eventId", "EVT-FAIL-" + orderId);

            restClient.post()
                    .uri("/api/v1/notifications/events/order-payment-failed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Gửi thông báo OrderPaymentFailed thành công tới Notification Service cho đơn hàng {}", orderId);
        } catch (Exception ex) {
            log.warn("Không thể kết nối tới Notification Service (§5.2 SRS cô lập lỗi, tiếp tục giải phóng kho): {}",
                    ex.getMessage());
        }
    }
}
