package com.nexus.commerce.client;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Gửi snapshot outbox bằng service account riêng; tái dùng bộ cache OAuth, không chuyển tiếp JWT buyer. */
@Component
public class NotificationDelivery {
    private final InventoryServiceToken tokens;
    private final RestClient client;

    public NotificationDelivery(@Value("${app.notification.url:http://localhost:8086}") String url,
            @Value("${app.notification.token-url:}") String tokenUrl,
            @Value("${app.notification.client-id:}") String clientId,
            @Value("${app.notification.client-secret:}") String secret) {
        tokens = new InventoryServiceToken(tokenUrl, clientId, secret);
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        // Cho phép notification hoàn tất tối đa ba lần thử SMTP có timeout hữu hạn.
        factory.setReadTimeout(Duration.ofSeconds(30));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    /** Thiếu OAuth thì giữ nguyên backlog, không gửi anonymous và không báo đã giao giả. */
    public boolean configured() { return tokens.configured(); }

    /** Chỉ worker gọi sau commit; 401 xóa cache để lần retry lấy token mới, mọi lỗi khác chuyển về outbox. */
    public void send(Map<String, Object> event) {
        try {
            var response = client.post().uri("/internal/commerce-notifications").headers(h -> h.setBearerAuth(tokens.get()))
                    .body(event).retrieve().toBodilessEntity();
            // Chỉ ACK 204 đúng contract, không đánh dấu done nếu proxy redirect hoặc trả trang HTML 200.
            if (response.getStatusCode().value() != 204) { throw new IllegalStateException("Notification không trả ACK 204"); }
            // Email dùng key riêng, dữ liệu snapshot từ DB; lỗi SMTP giữ toàn bộ outbox để retry sau commit thanh toán.
            if (event.get("recipientEmail") instanceof String email && !email.isBlank()) {
                var payload = new java.util.LinkedHashMap<String, Object>(event);
                payload.put("eventId", "EMAIL-" + event.get("id"));
                payload.put("eventType", switch (event.get("type").toString()) {
                    case "ORDER_CREATED" -> "ORDER_CREATED";
                    case "PAYMENT_SUCCESS" -> "ORDER_PAID";
                    case "PAYMENT_FAILED" -> "ORDER_PAYMENT_FAILED";
                    case "AUCTION_WON" -> "AUCTION_WON";
                    case "AUCTION_PAYMENT_REMINDER" -> "AUCTION_PAYMENT_REMINDER";
                    case "AUCTION_PAYMENT_TIMEOUT" -> "AUCTION_PAYMENT_TIMEOUT";
                    default -> throw new IllegalArgumentException("Sự kiện email không được hỗ trợ");
                });
                payload.put("totalAmount", event.get("amount"));
                var emailResponse = client.post().uri("/api/v1/notifications/transactional-email")
                        .headers(h -> h.setBearerAuth(tokens.get())).body(payload).retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<com.nexus.common.response.ApiResponse<Map<String, Object>>>() { });
                if (emailResponse == null || !emailResponse.success() || emailResponse.data() == null
                        || !"SENT".equals(emailResponse.data().get("status"))) {
                    throw new IllegalStateException("Email chưa gửi thành công; giữ outbox để retry");
                }
            }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) { tokens.invalidate(); }
            throw error;
        }
    }
}
