package com.nexus.fulfillment.client;

import com.nexus.common.response.ApiResponse;
import com.nexus.common.security.ClientCredentialsToken;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Đọc snapshot và gửi event Commerce bằng client_credentials riêng; không chuyển tiếp JWT nhân viên hoặc fallback dữ liệu giả. */
@Component
public class CommerceShippingClient {
    private final RestClient client;
    private final ClientCredentialsToken tokens;
    private final boolean configured;

    public CommerceShippingClient(@Value("${app.shipping.commerce.url:http://localhost:8083}") String url,
            @Value("${app.shipping.commerce.token-url:}") String tokenUrl,
            @Value("${app.shipping.commerce.client-id:nexus-fulfillment-service}") String clientId,
            @Value("${app.shipping.commerce.client-secret:}") String secret) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        tokens = new ClientCredentialsToken(tokenUrl, clientId, secret);
        configured = !tokenUrl.isBlank() && !clientId.isBlank() && !secret.isBlank();
    }

    /** Thiếu OAuth giữ backlog, không gửi sự kiện anonymous hoặc chuyển tiếp JWT người dùng. */
    public boolean configured() { return configured; }

    /** Gửi snapshot eventId cố định; chỉ ACK 204 mới hoàn tất. 401 xóa cache cho lần retry sau, không retry HTTP tại client. */
    public void sendEvent(java.util.Map<String, Object> event) {
        try {
            var response = client.post().uri("/api/v1/internal/commerce/shipping-events")
                    .headers(h -> h.setBearerAuth(tokens.get())).body(event).retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 204) { throw new DeliveryFailure(response.getStatusCode().value()); }
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) { tokens.invalidate(); }
            throw new DeliveryFailure(error.getStatusCode().value());
        } catch (org.springframework.web.client.RestClientException error) {
            throw new DeliveryFailure(0);
        }
    }

    /** Chỉ giữ status để phân loại poison/retry, không mang response body/cause có thể chứa PII. */
    public static class DeliveryFailure extends RuntimeException {
        private final int status;
        public DeliveryFailure(int status) { super("Shipping event chưa được ACK"); this.status = status; }
        public int status() { return status; }
    }

    /** Timeout/401/response sai phải dừng chuẩn bị kiện; chỉ lần gọi sau lấy token mới, không retry HTTP ngầm. */
    public Snapshot get(UUID orderId) {
        try {
            var response = client.get().uri("/api/v1/internal/commerce/shipping-orders/{id}", orderId)
                    .headers(h -> h.setBearerAuth(tokens.get())).retrieve()
                    .toEntity(new org.springframework.core.ParameterizedTypeReference<ApiResponse<Snapshot>>() { });
            var result = response.getBody();
            if (response.getStatusCode().value() != 200 || result == null || !result.success()
                    || result.data() == null || !orderId.equals(result.data().orderId())) {
                throw new IllegalStateException("Commerce shipping snapshot không hợp lệ");
            }
            return result.data();
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 401) { tokens.invalidate(); }
            // Không nối cause chứa URL/response PII; response upstream không đưa về browser hoặc log.
            throw new IllegalStateException("Không đọc được Commerce shipping snapshot");
        } catch (org.springframework.web.client.RestClientException error) {
            throw new IllegalStateException("Commerce shipping snapshot tạm thời không khả dụng");
        }
    }

    /** Chỉ gồm field contract !152; không nhận email/bằng chứng thanh toán hoặc coi total là quyền tự tạo shipment. */
    public record Snapshot(UUID orderId, String orderNo, String status, String recipientName, String recipientPhone,
            String shippingAddress, String paymentMethod, boolean priceFinalized, BigDecimal total, String fulfillmentStatus) { }
}
