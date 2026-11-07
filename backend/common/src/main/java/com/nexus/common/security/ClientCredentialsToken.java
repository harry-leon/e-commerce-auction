package com.nexus.common.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * Xin access token từ Keycloak bằng client_credentials cho một service account, không dùng quyền người dùng.
 * Cache trong RAM của từng instance; get() đồng bộ để các request đồng thời dùng chung một lần refresh.
 * Làm mới trước expires_in 30 giây, thiếu cấu hình/response sai/lỗi IAM thì thất bại và không dùng token cũ.
 * Không lưu DB hoặc log body/secret; downstream 401 gọi invalidate(), không tự retry mutation.
 */
public final class ClientCredentialsToken {
    private final String url;
    private final String clientId;
    private final String secret;
    private final HttpClient client;
    private String token;
    private Instant renewAt = Instant.EPOCH;

    public ClientCredentialsToken(String url, String clientId, String secret) {
        this.url = url;
        this.clientId = clientId;
        this.secret = secret;
        // JDK HTTP tránh Spring converter DEBUG ghi form client_secret; không bật log body/header nhạy cảm.
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Một refresh tại một thời điểm; token sống ngắn không cache để không dùng quá expiry. Không retry mutation tự động. */
    public synchronized String get() {
        if (url.isBlank() || clientId.isBlank() || secret.isBlank()) {
            throw new IllegalStateException("Thiếu cấu hình service account");
        }
        if (token != null && Instant.now().isBefore(renewAt)) { return token; }
        String form = "grant_type=client_credentials&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8);
        Token result;
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw new IllegalStateException("IAM từ chối service account"); }
            result = new ObjectMapper().readValue(response.body(), Token.class);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yêu cầu IAM bị gián đoạn");
        } catch (java.io.IOException error) {
            // Không đính kèm response/cause có thể chứa token hoặc credential trong lỗi JSON.
            throw new IllegalStateException("Không lấy được service token từ IAM");
        }
        if (result == null || result.value() == null || result.value().isBlank() || result.seconds() < 1) {
            throw new IllegalStateException("IAM trả service token không hợp lệ");
        }
        // Chỉ thay cache sau khi IAM trả đủ token/thời hạn; token sống dưới 30 giây không được tái sử dụng.
        token = result.value();
        renewAt = Instant.now().plusSeconds(Math.max(0, result.seconds() - 30));
        return token;
    }

    /** Caller xóa cache khi downstream trả 401; lần gọi sau lấy token mới, không gửi lại mutation mù. */
    public synchronized void invalidate() { token = null; renewAt = Instant.EPOCH; }

    // Keycloak còn trả scope/token_type/session_state; bỏ qua trường mở rộng, vẫn bắt buộc token và expires_in hợp lệ.
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record Token(@JsonProperty("access_token") String value, @JsonProperty("expires_in") long seconds) { }
}
