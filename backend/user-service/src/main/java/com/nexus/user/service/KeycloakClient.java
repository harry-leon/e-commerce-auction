package com.nexus.user.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Adapter IAM gọi Keycloak Admin API bằng client_credentials; không truy cập DB Keycloak.
 * Timeout hữu hạn, không retry mutation; lỗi provider trả 502 qua BusinessException và không lộ response/token.
 */
@Component
public class KeycloakClient {
    private final RestClient client;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String webClientId;

    public KeycloakClient(@Value("${app.keycloak.base-url:http://localhost:8180}") String baseUrl,
                          @Value("${app.keycloak.realm:nexus-realm}") String realm,
                          @Value("${app.keycloak.admin-client-id:nexus-admin-cli}") String clientId,
                          @Value("${app.keycloak.admin-client-secret:}") String clientSecret,
                          @Value("${app.keycloak.client-id:nexus-web}") String webClientId) {
        // Giới hạn cả kết nối TCP và đọc response để request IAM không giữ thread vô hạn.
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    if (response.getStatusCode().value() == 404) {
                        throw new ApiException("IAM_USER_NOT_FOUND", HttpStatus.NOT_FOUND,
                                "Không tìm thấy tài khoản trong Keycloak", null);
                    }
                    throw new ApiException("IAM_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY,
                            "Không thể thực hiện yêu cầu IAM", null);
                }).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.webClientId = webClientId;
    }

    /** Lấy token quản trị theo từng request; không log hoặc lưu token vào DB. */
    private String adminToken() {
        if (clientSecret.isBlank()) {
            throw new ApiException("IAM_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình Keycloak admin client", null);
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        Map<?, ?> response = client.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token)) {
            throw new ApiException("IAM_PROVIDER_ERROR", HttpStatus.BAD_GATEWAY, "IAM không trả token quản trị", null);
        }
        return token;
    }

    /** Service token chỉ dùng cho HTTP nội bộ có quyền PROFILE_WRITER; không gửi token quản trị ra trình duyệt. */
    public String serviceToken() { return adminToken(); }

    private RestClient.RequestHeadersSpec<?> get(String path, Object... variables) {
        return client.get().uri("/admin/realms/" + realm + path, variables).headers(headers -> headers.setBearerAuth(adminToken()));
    }

    /** Dữ liệu role API chỉ lấy các thuộc tính cần thiết; bỏ qua field nội bộ của Keycloak. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealmRole(String id, String name, String description, Map<String, List<String>> attributes) { }

    /** Chỉ liệt kê vai trò ứng dụng ROLE_*, bỏ default/realm-management và privilege nguyên tử. */
    public List<RealmRole> roles() {
        RealmRole[] values = get("/roles?briefRepresentation=false").retrieve().body(RealmRole[].class);
        return values == null ? List.of() : Arrays.stream(values)
                .filter(role -> role.name().startsWith("ROLE_") && !role.name().equals("ROLE_PROFILE_WRITER")).toList();
    }

    /** Privilege là realm role nguyên tử có mã dạng DOMAIN.ACTION, dùng để xây composite ứng dụng. */
    public List<RealmRole> privilegeRoles() {
        RealmRole[] values = get("/roles?briefRepresentation=false").retrieve().body(RealmRole[].class);
        return values == null ? List.of() : Arrays.stream(values).filter(role -> role.name().contains(".")).toList();
    }

    /** Trả danh sách privilege hiệu lực của role composite. */
    public Set<String> privileges(String roleId) {
        RealmRole[] values = get("/roles-by-id/{id}/composites", roleId).retrieve().body(RealmRole[].class);
        return values == null ? Set.of() : Arrays.stream(values).map(RealmRole::name)
                .filter(name -> name.contains(".")).collect(Collectors.toSet());
    }

    /** Đọc mappings hiệu lực gồm role và privilege; Keycloak thực hiện mở rộng composite. */
    public Set<String> authorities(UUID userId) {
        try {
            RealmRole[] values = get("/users/{id}/role-mappings/realm/composite", userId).retrieve().body(RealmRole[].class);
            return values == null ? Set.of() : Arrays.stream(values).map(RealmRole::name).collect(Collectors.toSet());
        } catch (ApiException exception) {
            // Hồ sơ DB vẫn được liệt kê nếu IAM đã mất user liên kết; chỉ role thiếu, lỗi IAM khác vẫn phải báo.
            if ("IAM_USER_NOT_FOUND".equals(exception.getCode())) { return Set.of(); }
            throw exception;
        }
    }

    /** Gán toàn bộ role ứng dụng sau khi xác minh tất cả mã; không đụng role hệ thống. */
    public void assignRoles(UUID userId, Set<String> codes) {
        List<RealmRole> desired = roles().stream()
                .filter(role -> codes.contains(role.name()) || codes.contains(role.name().substring(5))).toList();
        if (desired.size() != codes.size()) { throw new IllegalArgumentException("Có mã vai trò không tồn tại"); }
        RealmRole[] current = get("/users/{id}/role-mappings/realm", userId).retrieve().body(RealmRole[].class);
        List<RealmRole> old = current == null ? List.of() : Arrays.stream(current)
                .filter(role -> role.name().startsWith("ROLE_")).toList();
        // Thêm trước, xóa role dư sau; nếu provider lỗi, request thất bại và có thể thực hiện lại an toàn.
        mutate("POST", "/users/" + userId + "/role-mappings/realm", desired);
        List<RealmRole> removed = old.stream().filter(role -> desired.stream().noneMatch(item -> item.id().equals(role.id()))).toList();
        if (!removed.isEmpty()) { mutate("DELETE", "/users/" + userId + "/role-mappings/realm", removed); }
    }

    /** Khóa tài khoản và đăng xuất session trước khi profile bị xóa mềm. Access token cũ vẫn có thể sống tới expiry. */
    public void disable(UUID userId) {
        mutate("PUT", "/users/" + userId, Map.of("enabled", false));
        mutate("POST", "/users/" + userId + "/logout", Map.of());
    }

    /** Tạo/cập nhật/xóa IAM qua REST; không dùng transaction DB để giả lập rollback liên service. */
    public void mutate(String method, String path, Object body) {
        client.method(org.springframework.http.HttpMethod.valueOf(method)).uri("/admin/realms/" + realm + path)
                .headers(headers -> headers.setBearerAuth(adminToken())).contentType(MediaType.APPLICATION_JSON)
                .body(body).retrieve().toBodilessEntity();
    }

    /** Xác minh mật khẩu hiện tại bằng Keycloak và kiểm tra subject trước khi đổi credential. */
    public void changePassword(UserIdentity identity, String current, String replacement) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "password");
        form.add("client_id", webClientId);
        form.add("scope", "openid");
        form.add("username", identity.email());
        form.add("password", current);
        Map<?, ?> response = client.post().uri("/realms/{realm}/protocol/openid-connect/token", realm)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                .retrieve().onStatus(status -> status.value() == 401 || status.value() == 400, (request, result) -> {
                    throw new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID);
                }).body(Map.class);
        if (response == null || !response.containsKey("access_token")) {
            throw new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID);
        }
        // Email profile có thể cũ; token xác thực mật khẩu phải thuộc đúng Keycloak subject của người đổi.
        Map<?, ?> info = client.get().uri("/realms/{realm}/protocol/openid-connect/userinfo", realm)
                .headers(headers -> headers.setBearerAuth((String) response.get("access_token")))
                .retrieve().body(Map.class);
        if (info == null || !identity.id().toString().equals(info.get("sub"))) {
            throw new BusinessException(ErrorCode.AUTH_CREDENTIALS_INVALID);
        }
        mutate("PUT", "/users/" + identity.id() + "/reset-password", Map.of("type", "password", "value", replacement, "temporary", false));
        mutate("POST", "/users/" + identity.id() + "/logout", Map.of());
    }

    /** Gửi email action UPDATE_PASSWORD; Keycloak sở hữu token và màn hình reset, ứng dụng không nhận token đó. */
    public void sendResetEmail(UUID id) {
        mutate("PUT", "/users/" + id + "/execute-actions-email", List.of("UPDATE_PASSWORD"));
    }

    /** Định danh profile đã được ánh xạ từ JWT sub; email lấy trong DB, không nhận username tùy ý từ client. */
    public record UserIdentity(UUID id, String email) { }
}
