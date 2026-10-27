package com.nexus.gateway.controller;

import com.nexus.gateway.config.PasswordPolicy;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Controller xử lý luồng xác thực và điều phối đăng ký tập trung tại API Gateway (BFF).
 *
 * Chức năng:
 * 1. Tiếp nhận đăng nhập/cấp mới token, ủy thác xác thực trực tiếp sang Keycloak qua Direct Access Grants.
 * 2. Điều phối quy trình đăng ký tài khoản (Orchestration):
 *    - Tạo tài khoản và thông tin bảo mật (credentials) trên Keycloak Admin REST API.
 *    - Lấy keycloak_id (UUID) từ phản hồi của Keycloak.
 *    - Chuyển tiếp hồ sơ người dùng (không chứa mật khẩu) kèm header X-User-Id tới user-service để lưu DB.
 *    - Thực hiện bù trừ (rollback xóa tài khoản Keycloak) nếu lưu hồ sơ phía user-service gặp sự cố.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthGatewayController {

    private static final Logger log = LoggerFactory.getLogger(AuthGatewayController.class);

    private final WebClient webClient;

    @Value("${app.keycloak.token-uri}")
    private String tokenUri;

    @Value("${app.keycloak.client-id:nexus-web}")
    private String clientId;

    @Value("${app.keycloak.base-url:http://localhost:8180}")
    private String baseUrl;

    @Value("${app.keycloak.realm:nexus-realm}")
    private String realm;

    @Value("${app.keycloak.admin-client-id:nexus-admin-cli}")
    private String adminClientId;

    @Value("${app.keycloak.admin-client-secret:}")
    private String adminClientSecret;

    @Value("${app.keycloak.user-service-url:http://localhost:8081}")
    private String userServiceUrl;

    public AuthGatewayController(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    /**
     * Luồng Đăng nhập: Tiếp nhận email/username và password dạng JSON,
     * ủy thác xác thực hoàn toàn cho Keycloak.
     *
     * @param body Payload JSON chứa email/username và password
     * @return Mono chứa JWT token trả về từ Keycloak hoặc lỗi HTTP 401
     */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map>> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", body.get("email"));
        String password = body.get("password");

        if (username == null || password == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", Map.of("code", "AUTH_CREDENTIALS_INVALID", "message", "Username and password are required")
            )));
        }

        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "password")
                .with("client_id", clientId)
                .with("scope", "openid")
                .with("username", username)
                .with("password", password))
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(java.time.Duration.ofSeconds(15))
            .flatMap(this::withProfile)
            .onErrorResume(WebClientResponseException.class, ex -> {
                HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
                return Mono.just(ResponseEntity.status(status != null ? status : HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", Map.of("code", "AUTH_CREDENTIALS_INVALID", "message", "Email hoặc mật khẩu không chính xác hoặc tài khoản bị khóa")
                )));
            });
    }

    /**
     * Luồng Cấp mới Token: Tiếp nhận refreshToken dạng JSON và gửi yêu cầu cấp mới tới Keycloak.
     *
     * @param body Payload JSON chứa refreshToken
     * @return Mono chứa cặp token mới
     */
    @PostMapping(value = {"/refresh-token", "/refresh"}, consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map>> refreshToken(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        if (refreshToken == null) {
            refreshToken = body.get("refresh_token");
        }

        if (refreshToken == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", Map.of("code", "AUTH_REFRESH_TOKEN_EXPIRED", "message", "Refresh token is required")
            )));
        }

        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                .with("client_id", clientId)
                .with("refresh_token", refreshToken))
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(java.time.Duration.ofSeconds(15))
            .flatMap(this::withProfile)
            .onErrorResume(WebClientResponseException.class, ex -> Mono.just(
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "error", Map.of("code", "AUTH_REFRESH_TOKEN_EXPIRED", "message", "Refresh token không hợp lệ hoặc đã hết hạn")
                ))
            ));
    }

    /**
     * Luồng Đăng ký & Đồng bộ Hồ sơ (BFF Orchestration):
     * 1. Kiểm tra validation cơ bản (email, password).
     * 2. Lấy access token quản trị từ Keycloak qua luồng client_credentials.
     * 3. Gọi Keycloak Admin API tạo tài khoản và lưu mật khẩu an toàn.
     * 4. Trích xuất keycloak_id (UUID) từ header Location của Keycloak.
     * 5. Gửi request sang user-service tạo hồ sơ cá nhân với header X-User-Id.
     * 6. Nếu lưu hồ sơ thất bại, thực hiện bù trừ (rollback) xóa user trên Keycloak để bảo toàn tính nhất quán.
     *
     * @param body Payload JSON chứa thông tin đăng ký và hồ sơ người dùng
     * @return Mono chứa thông tin hồ sơ đã tạo hoặc thông báo lỗi
     */
    @PostMapping(value = "/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map>> register(@RequestBody Map<String, Object> body) {
        // Từ chối scalar sai kiểu trước khi gọi IAM, tránh ClassCastException trả 500 cho payload không hợp lệ.
        String email = body.get("email") instanceof String value ? value.trim().toLowerCase(java.util.Locale.ROOT) : null;
        String password = body.get("password") instanceof String value ? value : null;
        String fullName = body.get("fullName") instanceof String value ? value.trim() : null;

        if (email == null || email.isBlank() || !email.contains("@") || fullName == null || fullName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", Map.of("code", "INVALID_ARGUMENT", "message", "Email hợp lệ và họ tên không được để trống")
            )));
        }

        // Trả 422 kèm từng điều kiện thiếu trước khi gọi IAM; không tạo tài khoản rồi mới phát hiện mật khẩu yếu.
        var passwordErrors = PasswordPolicy.errors(password);
        if (!passwordErrors.isEmpty()) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(Map.of(
                "success", false,
                "error", Map.of("code", "VALIDATION_ERROR", "message", "Mật khẩu chưa đạt yêu cầu", "details", passwordErrors)
            )));
        }

        // Bước 1: Lấy Admin Access Token từ Keycloak
        return fetchKeycloakAdminToken()
            .flatMap(adminToken -> {
                // Bước 2: Tạo User trên Keycloak Admin API
                return createKeycloakUser(adminToken, email, password, fullName)
                    .flatMap(keycloakId -> {
                        // Bước 3: Tạo Profile trên user-service
                        Map<String, Object> profilePayload = buildProfilePayload(body, email, fullName);

                        return webClient.post()
                            .uri(userServiceUrl + "/api/v1/users")
                            .headers(headers -> headers.setBearerAuth(adminToken))
                            .header("X-Profile-Subject", keycloakId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(profilePayload)
                            .retrieve()
                            .bodyToMono(Map.class)
            .timeout(java.time.Duration.ofSeconds(15))
                            .map(userProfileResponse -> ResponseEntity.status(HttpStatus.CREATED).body(userProfileResponse))
                            .onErrorResume(error -> {
                                log.error("Lỗi khi tạo hồ sơ tại user-service, tiến hành rollback Keycloak user: {}", keycloakId, error);
                                // Bù trừ: Xóa tài khoản Keycloak đã tạo để đảm bảo toàn vẹn dữ liệu
                                return rollbackKeycloakUser(adminToken, keycloakId)
                                    .then(Mono.error(error));
                            });
                    });
            })
            .onErrorResume(ResponseStatusException.class, ex -> {
                HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
                return Mono.just(ResponseEntity.status(status != null ? status : HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "error", Map.of("code", "REGISTRATION_FAILED", "message", ex.getReason() != null ? ex.getReason() : "Đăng ký thất bại")
                )));
            })
            .onErrorResume(WebClientResponseException.class, ex -> {
                HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
                String message = "Không thể hoàn tất đăng ký";
                if (status == HttpStatus.CONFLICT || ex.getStatusCode().value() == 409) {
                    message = "Email đã được đăng ký trong hệ thống";
                }
                return Mono.just(ResponseEntity.status(status != null ? status : HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "error", Map.of("code", "REGISTRATION_FAILED", "message", message.isEmpty() ? "Đăng ký tài khoản thất bại" : message)
                )));
            })
            .onErrorResume(Exception.class, ex -> {
                log.error("Lỗi hệ thống trong luồng đăng ký:", ex);
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "error", Map.of("code", "INTERNAL_SERVER_ERROR", "message", "Không thể hoàn tất đăng ký, vui lòng thử lại sau")
                )));
            });
    }

    /**
     * Giữ contract frontend accessToken/refreshToken/user; lấy profile qua API bằng JWT đã được Keycloak cấp.
     * Lỗi profile dừng login/refresh, không tạo session UI thiếu user.
     */
    private Mono<ResponseEntity<Map>> withProfile(Map tokens) {
        String accessToken = (String) tokens.get("access_token");
        return webClient.get().uri(userServiceUrl + "/api/v1/users/me")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().bodyToMono(Map.class).timeout(java.time.Duration.ofSeconds(15))
            .map(profile -> {
                Map<String, Object> result = new HashMap<>();
                result.put("accessToken", accessToken);
                result.put("refreshToken", tokens.get("refresh_token"));
                result.put("expiresIn", tokens.get("expires_in"));
                result.put("tokenType", "Bearer");
                result.put("user", profile.containsKey("data") ? profile.get("data") : profile);
                return ResponseEntity.ok((Map) result);
            });
    }

    /**
     * Lấy Admin Token từ Keycloak thông qua giao thức OAuth2 client_credentials.
     */
    private Mono<String> fetchKeycloakAdminToken() {
        if (adminClientSecret.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "IAM chưa được cấu hình"));
        }
        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", adminClientId)
                .with("client_secret", adminClientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(java.time.Duration.ofSeconds(15))
            .map(res -> (String) res.get("access_token"));
    }

    /**
     * Tạo tài khoản mới trong Keycloak Admin REST API và trích xuất UUID (keycloak_id).
     */
    private Mono<String> createKeycloakUser(String adminToken, String email, String password, String fullName) {
        String adminUsersUri = baseUrl + "/admin/realms/" + realm + "/users";
        // User Profile của Keycloak yêu cầu cả firstName/lastName; thiếu lastName làm password grant bị account-not-fully-set-up.
        // Profile nghiệp vụ vẫn giữ fullName nguyên vẹn; tên chỉ có một từ được dùng cho cả hai field IAM.
        String normalizedName = fullName == null || fullName.isBlank() ? email : fullName.trim();
        int separator = normalizedName.lastIndexOf(' ');
        String firstName = separator < 0 ? normalizedName : normalizedName.substring(separator + 1);
        String lastName = separator < 0 ? normalizedName : normalizedName.substring(0, separator);

        Map<String, Object> keycloakUser = Map.of(
            "username", email.trim().toLowerCase(),
            "email", email.trim().toLowerCase(),
            "firstName", firstName,
            "lastName", lastName,
            "enabled", true,
            "emailVerified", false,
            "credentials", List.of(Map.of(
                "type", "password",
                "value", password,
                "temporary", false
            ))
        );

        return webClient.post()
            .uri(adminUsersUri)
            .header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(keycloakUser)
            .exchangeToMono(clientResponse -> {
                if (clientResponse.statusCode().value() == 201) {
                    URI location = clientResponse.headers().asHttpHeaders().getLocation();
                    if (location != null) {
                        String path = location.getPath();
                        String keycloakId = path.substring(path.lastIndexOf('/') + 1);
                        // Admin create-user không bảo đảm realmRoles trong payload được áp dụng.
                        // Gán BUYER rõ ràng; lỗi ở bước này phải xóa tài khoản vừa tạo.
                        return assignDefaultBuyer(adminToken, keycloakId)
                            .thenReturn(keycloakId)
                            .onErrorResume(error -> rollbackKeycloakUser(adminToken, keycloakId).then(Mono.error(error)));
                    }
                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Không lấy được keycloak_id từ phản hồi Keycloak"));
                } else if (clientResponse.statusCode().value() == 409) {
                    return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT, "Email đã được đăng ký trong hệ thống"));
                } else {
                    return clientResponse.createException().flatMap(Mono::error);
                }
            });
    }

    /**
     * Bù trừ (Saga Rollback): Xóa user khỏi Keycloak nếu bước lưu hồ sơ tại user-service gặp lỗi.
     */
    private Mono<Void> rollbackKeycloakUser(String adminToken, String keycloakId) {
        String userDeleteUri = baseUrl + "/admin/realms/" + realm + "/users/" + keycloakId;
        return webClient.delete()
            .uri(userDeleteUri)
            .header("Authorization", "Bearer " + adminToken)
            .retrieve()
            .bodyToMono(Void.class)
            .onErrorResume(e -> {
                log.warn("Không thể xóa Keycloak user trong bước bù trừ: {}", keycloakId, e);
                return Mono.empty();
            });
    }

    /** Gán realm role BUYER trên Keycloak trước khi tạo profile; không tin role từ payload frontend. */
    private Mono<Void> assignDefaultBuyer(String adminToken, String keycloakId) {
        String adminBase = baseUrl + "/admin/realms/" + realm;
        return webClient.get().uri(adminBase + "/roles/ROLE_BUYER")
            .headers(headers -> headers.setBearerAuth(adminToken))
            .retrieve().bodyToMono(Map.class).timeout(java.time.Duration.ofSeconds(15))
            .flatMap(role -> webClient.post().uri(adminBase + "/users/" + keycloakId + "/role-mappings/realm")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(List.of(role))
                .retrieve().bodyToMono(Void.class).timeout(java.time.Duration.ofSeconds(15)));
    }

    /**
     * Chuẩn bị dữ liệu hồ sơ gửi sang user-service (hoàn toàn không mang theo mật khẩu).
     */
    private Map<String, Object> buildProfilePayload(Map<String, Object> body, String email, String fullName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email.trim().toLowerCase());
        payload.put("fullName", fullName != null ? fullName.trim() : email);

        if (body.get("phone") != null) payload.put("phone", body.get("phone"));
        if (body.get("identifyNumber") != null) payload.put("identifyNumber", body.get("identifyNumber"));
        if (body.get("gender") != null) payload.put("gender", body.get("gender"));
        if (body.get("dateOfBirth") != null) payload.put("dateOfBirth", body.get("dateOfBirth"));
        if (body.get("address") != null) payload.put("address", body.get("address"));

        return payload;
    }
}
