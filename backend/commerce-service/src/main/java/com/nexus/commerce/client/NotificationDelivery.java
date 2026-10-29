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
        factory.setReadTimeout(Duration.ofSeconds(5));
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
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) { tokens.invalidate(); }
            throw error;
        }
    }
}
