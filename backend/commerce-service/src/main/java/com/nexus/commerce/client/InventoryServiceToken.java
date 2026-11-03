package com.nexus.commerce.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** OAuth client_credentials riêng cho outbox; token chỉ cache RAM, không lưu token buyer/secret trong database hoặc log. */
@Component
public class InventoryServiceToken {
    private final String url;
    private final String clientId;
    private final String secret;
    private final RestClient client;
    private String cached;
    private Instant renewAt = Instant.EPOCH;

    public InventoryServiceToken(@Value("${app.payment.inventory.token-url:}") String url,
                                  @Value("${app.payment.inventory.client-id:}") String clientId,
                                  @Value("${app.payment.inventory.client-secret:}") String secret) {
        this.url = url;
        this.clientId = clientId;
        this.secret = secret;
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }

    /** Thiếu service account thì tác vụ giữ PENDING trong outbox, không tự dùng token/mock không có quyền. */
    public boolean configured() { return !url.isBlank() && !clientId.isBlank() && !secret.isBlank(); }

    /** Đồng bộ refresh trong một instance, làm mới trước expiry; mỗi instance vẫn có cache token độc lập. */
    public synchronized String get() {
        if (!configured()) { throw new IllegalStateException("Chưa cấu hình service account kho"); }
        if (cached != null && Instant.now().isBefore(renewAt)) { return cached; }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        Token result = client.post().uri(url).contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Token.class);
        if (result == null || result.accessToken() == null || result.accessToken().isBlank() || result.expiresIn() < 1) {
            throw new IllegalStateException("Keycloak trả token service không hợp lệ");
        }
        cached = result.accessToken();
        renewAt = Instant.now().plusSeconds(Math.max(1, result.expiresIn() - 30));
        return cached;
    }

    /** Khi kho trả 401 thì lần retry sau phải lấy token mới, không tiếp tục gửi cache đã bị thu hồi. */
    public synchronized void invalidate() { cached = null; renewAt = Instant.EPOCH; }
    private record Token(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) { }
}
