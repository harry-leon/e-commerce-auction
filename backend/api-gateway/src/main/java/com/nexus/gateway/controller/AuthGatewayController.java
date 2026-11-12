package com.nexus.gateway.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.gateway.config.PasswordPolicy;
import com.nexus.gateway.config.RsaCryptoService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BFF đăng nhập/refresh qua Keycloak rồi nạp hồ sơ bằng JWT mới cấp.
 * Đăng ký tạo IAM user, gán BUYER và lưu profile tại user-service.
 * Service JWT và X-Profile-Subject liên kết profile với UUID từ IAM;
 * mật khẩu chỉ gửi tới IAM, không được đưa vào payload hồ sơ.
 * Lỗi gán role/lưu profile sẽ xóa IAM user; lỗi cleanup chỉ ghi log
 * để đối soát, chưa có retry bền vững.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthGatewayController {

    /**
     * Cho UI biết có cookie để khôi phục phiên, tránh gọi me/refresh khi khách chưa đăng nhập.
     * Không trả token hoặc xác nhận JWT hợp lệ; me/refresh vẫn xác thực ở bước sau.
     * Không cache kết quả vì cookie có thể đổi ngay sau login/logout.
     */
    @GetMapping("/session")
    public ResponseEntity<Map<String, Boolean>> session(
            @CookieValue(name = "nexus_access", required = false) String access,
            @CookieValue(name = "nexus_refresh", required = false) String refresh) {
        boolean present = (access != null && !access.isBlank())
                || (refresh != null && !refresh.isBlank());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(Map.of("hasSessionCookies", present));
    }

    /** Logger điều phối, không ghi token hay mật khẩu. */
    private static final Logger LOG =
        LoggerFactory.getLogger(AuthGatewayController.class);

    /**
     * Timeout cho mỗi request IAM/profile. Tăng từ 15s lên 60s để đáp ứng độ trễ
     * của chuỗi truy vấn xác thực người dùng tới cơ sở dữ liệu Supabase tại Tokyo.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** Hạn dự phòng khi IAM không trả refresh_expires_in, tính bằng giây. */
    private static final int DEFAULT_REFRESH_SECONDS = 1800;

    /** Client reactive dùng chung cho IAM và profile. */
    private final WebClient webClient;

    /** Dịch vụ cung cấp Public Key và giải mã mật khẩu RSA từ Frontend. */
    private final RsaCryptoService rsaCryptoService;

    /** Endpoint cấp token Keycloak. */
    @Value("${app.keycloak.token-uri}")
    private String tokenUri;

    /** Client đăng nhập của ứng dụng web. */
    @Value("${app.keycloak.client-id:nexus-web}")
    private String clientId;

    /** URL IAM nội bộ cho Admin REST API. */
    @Value("${app.keycloak.base-url:http://localhost:8180}")
    private String baseUrl;

    /** Realm chứa người dùng và role Nexus. */
    @Value("${app.keycloak.realm:nexus-realm}")
    private String realm;

    /** Client service account tạo tài khoản và gán role. */
    @Value("${app.keycloak.admin-client-id:nexus-admin-cli}")
    private String adminClientId;

    /** Secret lấy từ cấu hình; thiếu secret thì từ chối đăng ký. */
    @Value("${app.keycloak.admin-client-secret:}")
    private String adminClientSecret;

    /** URL service lưu hồ sơ nghiệp vụ, không lưu password IAM. */
    @Value("${app.keycloak.user-service-url:http://localhost:8081}")
    private String userServiceUrl;

    /** Production bật Secure; localhost HTTP đặt AUTH_COOKIE_SECURE=false. */
    @Value("${app.auth.cookie-secure:true}")
    private boolean cookieSecure = true;

    /**
     * Dùng builder đã cấu hình connector/timeout của Gateway và nạp RsaCryptoService.
     *
     * @param webClientBuilder builder do Spring cấp
     * @param rsaCryptoService dịch vụ giải mã RSA mật khẩu phía client
     */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthGatewayController(final WebClient.Builder webClientBuilder,
            final RsaCryptoService rsaCryptoService) {
        this.webClient = webClientBuilder.build();
        this.rsaCryptoService = rsaCryptoService;
    }

    /** Constructor tương thích ngược cho unit test. */
    public AuthGatewayController(final WebClient.Builder webClientBuilder) {
        this(webClientBuilder, new RsaCryptoService());
    }

    /**
     * Cung cấp Public Key RSA của Gateway cho Frontend để mã hóa mật khẩu trước khi gửi request.
     * Cho phép client cache key trong 1 giờ để giảm tải cho Gateway.
     *
     * @return ApiResponse chứa publicKey Base64
     */
    @GetMapping("/public-key")
    public ResponseEntity<ApiResponse<Map<String, String>>> getPublicKey() {
        return ResponseEntity.ok()
            .cacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofHours(1)))
            .body(ApiResponse.ok(Map.of("publicKey", rsaCryptoService.getPublicKeyBase64())));
    }

    /**
     * Đăng nhập qua Keycloak rồi nạp profile bằng JWT vừa cấp.
     * Lỗi profile dừng login để tránh session UI thiếu user.
     * @param body email/username và mật khẩu gốc
     * @return envelope token/profile hoặc lỗi xác thực
     */
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>>
        login(@RequestBody final Map<String, String> body) {
        String username = body.getOrDefault("username", body.get("email"));
        // Ưu tiên đọc encryptedPassword được mã hóa RSA từ Frontend; nếu không có thì đọc password thường.
        String rawPassword = body.get("encryptedPassword");
        if (rawPassword == null || rawPassword.isBlank()) {
            rawPassword = body.get("password");
        }
        String password = rsaCryptoService.decrypt(rawPassword);

        if (username == null || password == null) {
            return Mono.just(ResponseEntity.badRequest().body(
                ApiResponse.error("AUTH_CREDENTIALS_INVALID",
                    "Username and password are required")
            ));
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
            .timeout(REQUEST_TIMEOUT)
            .flatMap(this::withProfile)
            .onErrorResume(WebClientResponseException.class, ex -> {
                HttpStatus status =
                    HttpStatus.resolve(ex.getStatusCode().value());
                return Mono.just(ResponseEntity.status(status != null ? status
                    : HttpStatus.UNAUTHORIZED).body(
                    ApiResponse.error("AUTH_CREDENTIALS_INVALID",
                        "Email hoặc mật khẩu không chính xác hoặc tài khoản bị"
                        + " khóa")
                ));
            });
    }

    /**
     * Cấp lại token qua IAM rồi nạp profile như luồng login.
     * @param refreshToken cookie HttpOnly, không nhận token từ JSON
     * @return token/profile mới hoặc lỗi hết phiên
     */
    @PostMapping(value = {"/refresh-token", "/refresh"})
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>>
        refreshToken(@CookieValue(name = "nexus_refresh", required = false)
            final String refreshToken) {

        if (refreshToken == null) {
            return Mono.just(ResponseEntity.badRequest().body(
                ApiResponse.error("AUTH_REFRESH_TOKEN_EXPIRED",
                    "Refresh token is required")
            ));
        }

        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                .with("client_id", clientId)
                .with("refresh_token", refreshToken))
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(REQUEST_TIMEOUT)
            .flatMap(this::withProfile)
            .onErrorResume(WebClientResponseException.class, ex -> Mono.just(
                ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.SET_COOKIE,
                        cookie("nexus_access", "", "/", 0),
                        cookie("nexus_refresh", "", "/api/v1/auth", 0)).body(
                    ApiResponse.error("AUTH_REFRESH_TOKEN_EXPIRED",
                        "Refresh token không hợp lệ hoặc đã hết hạn")
                )
            ));
    }

    /**
     * Validate email/tên/password/CCCD/số di động rồi tạo user IAM và profile.
     * Gán role BUYER từ server; lỗi gán role/lưu profile sẽ bù trừ.
     * @param body dữ liệu đăng ký; password chỉ gửi IAM
     * @return hồ sơ đã tạo (201) hoặc lỗi nghiệp vụ/hạ tầng
     */
    @PostMapping(value = "/register", consumes =
        MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ApiResponse<Object>>> register(@RequestBody
        final Map<String, Object> body) {
        // Từ chối scalar sai kiểu trước khi gọi IAM, tránh ClassCastException
        // trả 500 cho payload không hợp lệ.
        String email = body.get("email") instanceof String value
            ? value.trim().toLowerCase(java.util.Locale.ROOT) : null;
        // Ưu tiên giải mã RSA nếu nhận encryptedPassword từ Frontend
        Object encryptedPwdObj = body.get("encryptedPassword");
        Object plainPwdObj = body.get("password");
        String passwordRaw = encryptedPwdObj instanceof String s && !s.isBlank() ? s
            : (plainPwdObj instanceof String s2 ? s2 : null);
        String password = rsaCryptoService.decrypt(passwordRaw);
        String fullName = body.get("fullName") instanceof String value
            ? value.trim() : null;

        if (email == null || email.isBlank() || !email.contains("@")
            || fullName == null || fullName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(
                ApiResponse.error("INVALID_ARGUMENT",
                    "Email hợp lệ và họ tên không được để trống")
            ));
        }

        // Trả 422 kèm từng điều kiện thiếu trước khi gọi IAM; không tạo tài
        // khoản rồi mới phát hiện mật khẩu yếu.
        var passwordErrors = PasswordPolicy.errors(password);
        if (!passwordErrors.isEmpty()) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR",
                    "Mật khẩu chưa đạt yêu cầu", passwordErrors)
            ));
        }

        // Chặn CCCD thiếu/sai kiểu/sai 12 chữ số trước khi tạo IAM để không phát sinh
        // tài khoản rồi mới rollback. Trả field identifyNumber cho FE; không lộ số đã nhập.
        // Chỉ kiểm tra định dạng ASCII, không xác minh danh tính hoặc kiểm tra trùng CCCD.
        Object identifyNumber = body.get("identifyNumber");
        if (!(identifyNumber instanceof String number)
                || !number.matches("[0-9]{12}")) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR", "Số CCCD không hợp lệ",
                    List.of(Map.of("field", "identifyNumber", "code", "PATTERN",
                        "message", "Số CCCD phải gồm đúng 12 chữ số")))
            ));
        }

        // Phone tùy chọn; nếu có phải là chuỗi số di động nội địa 10 chữ số.
        // Chặn trước IAM để tránh tạo rồi rollback tài khoản vì số 11 chữ số, +84 hoặc sai đầu số.
        Object phone = body.get("phone");
        if (phone != null && (!(phone instanceof String phoneNumber)
                || !phoneNumber.matches("(?:|0[35789][0-9]{8})"))) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR", "Số điện thoại không hợp lệ",
                    List.of(Map.of("field", "phone", "code", "PATTERN",
                        "message", "Số điện thoại phải có đúng 10 chữ số và bắt đầu bằng 03, 05, 07, 08 hoặc 09")))
            ));
        }

        // Bước 1: Lấy Admin Access Token từ Keycloak
        return fetchKeycloakAdminToken()
            .flatMap(adminToken -> createKeycloakUser(adminToken, email,
                password, fullName)
                .flatMap(keycloakId -> createProfile(adminToken, keycloakId,
                    buildProfilePayload(body, email, fullName))))
            .onErrorResume(ResponseStatusException.class, ex -> {
                HttpStatus status =
                    HttpStatus.resolve(ex.getStatusCode().value());
                return Mono.just(ResponseEntity.status(status != null ? status
                    : HttpStatus.BAD_REQUEST).body(
                    ApiResponse.error("REGISTRATION_FAILED", ex.getReason()
                        != null ? ex.getReason() : "Đăng ký thất bại")
                ));
            })
            .onErrorResume(WebClientResponseException.class, ex -> {
                HttpStatus status =
                    HttpStatus.resolve(ex.getStatusCode().value());
                String message = "Không thể hoàn tất đăng ký";
                if (status == HttpStatus.CONFLICT || ex.getStatusCode().value()
                    == HttpStatus.CONFLICT.value()) {
                    message = "Email đã được đăng ký trong hệ thống";
                }
                return Mono.just(ResponseEntity.status(status != null ? status
                    : HttpStatus.BAD_REQUEST).body(
                    ApiResponse.error("REGISTRATION_FAILED", message.isEmpty()
                        ? "Đăng ký tài khoản thất bại" : message)
                ));
            })
            .onErrorResume(Exception.class, ex -> {
                LOG.error("Lỗi hệ thống trong luồng đăng ký:", ex);
                return
                    Mono.just(ResponseEntity.status(HttpStatus
                    .INTERNAL_SERVER_ERROR).body(
                    ApiResponse.error("INTERNAL_SERVER_ERROR",
                        "Không thể hoàn tất đăng ký, vui lòng thử lại sau")
                ));
            });
    }

    /**
     * Đọc profile bằng JWT mới và bọc contract frontend.
     * Hỗ trợ DTO thô hoặc envelope; không tự tạo profile giả.
     * @param tokens phản hồi token của Keycloak
     * Token chỉ đặt trong cookie HttpOnly sau khi đọc profile thành công.
     * @return envelope hồ sơ và hạn token; lỗi profile được truyền lên
     */
    private Mono<ResponseEntity<ApiResponse<Map<String, Object>>>>
        withProfile(final Map tokens) {
        String accessToken = (String) tokens.get("access_token");
        return webClient.get().uri(userServiceUrl + "/api/v1/users/me")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().bodyToMono(Map.class).timeout(REQUEST_TIMEOUT)
            .map(profile -> {
                Map<String, Object> result = new HashMap<>();
                result.put("expiresIn", tokens.get("expires_in"));
                result.put("tokenType", "Bearer");
                result.put("user", profile.containsKey("data")
                    ? profile.get("data") : profile);
                return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header(HttpHeaders.SET_COOKIE,
                        cookie("nexus_access", accessToken, "/",
                            ((Number) tokens.get("expires_in")).longValue()),
                        cookie("nexus_refresh",
                            (String) tokens.get("refresh_token"),
                            "/api/v1/auth", ((Number) tokens.getOrDefault(
                                "refresh_expires_in", DEFAULT_REFRESH_SECONDS))
                                .longValue()))
                    .body(ApiResponse.ok(result));
            });
    }

    /**
     * Xóa cookie đúng path; chưa thu hồi IAM session/JWT đã cấp.
     * @return HTTP 204 kèm cookie hết hạn
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        return ResponseEntity.noContent()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.SET_COOKIE, cookie("nexus_access", "", "/", 0),
                cookie("nexus_refresh", "", "/api/v1/auth", 0)).build();
    }

    /**
     * Cookie host-only chặn JS đọc; Strict hạn chế gửi từ site khác.
     * @param name tên cookie xác thực
     * @param value token hoặc rỗng khi xóa
     * @param path phạm vi đường dẫn
     * @param seconds hạn IAM tính bằng giây, 0 để xóa
     * @return giá trị Set-Cookie có HttpOnly và cờ Secure theo môi trường
     */
    private String cookie(final String name, final String value,
            final String path, final long seconds) {
        return ResponseCookie.from(name, value).httpOnly(true)
            .secure(cookieSecure).sameSite("Strict").path(path)
            .maxAge(seconds).build().toString();
    }

    /**
     * Lấy JWT service account qua client_credentials.
     * Thiếu secret trả 503 trước khi gọi IAM; không log credential.
     * @return access token dùng quản trị tài khoản IAM
     */
    private Mono<String> fetchKeycloakAdminToken() {
        if (adminClientSecret.isBlank()) {
            return Mono.error(new
                ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "IAM chưa được cấu hình"));
        }
        return webClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", adminClientId)
                .with("client_secret", adminClientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(REQUEST_TIMEOUT)
            .map(res -> (String) res.get("access_token"));
    }

    /**
     * Tạo IAM user, lấy UUID từ Location và gán BUYER.
     * Tên một từ dùng cho firstName/lastName; lỗi gán role sẽ bù trừ.
     * @param adminToken JWT service account IAM
     * @param email email đã kiểm tra
     * @param password mật khẩu gốc chỉ gửi IAM
     * @param fullName tên hồ sơ để suy ra firstName/lastName
     * @return UUID user đã gán role hoặc lỗi IAM
     */
    private Mono<String> createKeycloakUser(final String adminToken, final
        String email, final String password, final String fullName) {
        String adminUsersUri = baseUrl + "/admin/realms/" + realm + "/users";
        // User Profile của Keycloak yêu cầu cả firstName/lastName; thiếu
        // lastName làm password grant bị account-not-fully-set-up.
        // Profile nghiệp vụ vẫn giữ fullName nguyên vẹn; tên chỉ có một từ được
        // dùng cho cả hai field IAM.
        String normalizedName = fullName == null || fullName.isBlank() ? email
            : fullName.trim();
        int separator = normalizedName.lastIndexOf(' ');
        String firstName = separator < 0 ? normalizedName
            : normalizedName.substring(separator + 1);
        String lastName = separator < 0 ? normalizedName
            : normalizedName.substring(0, separator);

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
                if (clientResponse.statusCode().value()
                    == HttpStatus.CREATED.value()) {
                    URI location =
                        clientResponse.headers().asHttpHeaders().getLocation();
                    if (location != null) {
                        String path = location.getPath();
                        String keycloakId =
                            path.substring(path.lastIndexOf('/') + 1);
                        // Admin create-user không bảo đảm realmRoles trong
                        // payload được áp dụng.
                        // Gán BUYER rõ ràng; lỗi ở bước này phải xóa tài khoản
                        // vừa tạo.
                        return assignDefaultBuyer(adminToken, keycloakId)
                            .thenReturn(keycloakId)
                            .onErrorResume(error ->
                                rollbackKeycloakUser(adminToken,
                                keycloakId).then(Mono.error(error)));
                    }
                    return Mono.error(new
                        ResponseStatusException(HttpStatus
                        .INTERNAL_SERVER_ERROR,
                        "Không lấy được keycloak_id từ phản hồi Keycloak"));
                } else if (clientResponse.statusCode().value()
                    == HttpStatus.CONFLICT.value()) {
                    return Mono.error(new
                        ResponseStatusException(HttpStatus.CONFLICT,
                        "Email đã được đăng ký trong hệ thống"));
                } else {
                    return
                        clientResponse.createException().flatMap(Mono::error);
                }
            });
    }

    /**
     * Xóa user IAM nếu gán role hoặc lưu profile thất bại.
     * Lỗi cleanup được log và bỏ qua để giữ lỗi gốc; chưa có retry.
     * @param adminToken JWT service account IAM
     * @param keycloakId UUID user cần bù trừ
     * @return tín hiệu hoàn tất cleanup hoặc đã log lỗi
     */
    private Mono<Void> rollbackKeycloakUser(final String adminToken, final
        String keycloakId) {
        String userDeleteUri = baseUrl + "/admin/realms/" + realm + "/users/"
            + keycloakId;
        return webClient.delete()
            .uri(userDeleteUri)
            .header("Authorization", "Bearer " + adminToken)
            .retrieve()
            .bodyToMono(Void.class)
            .onErrorResume(e -> {
                LOG.warn("Không thể xóa Keycloak user trong bước bù trừ: {}",
                    keycloakId, e);
                return Mono.empty();
            });
    }

    /**
     * Gán ROLE_BUYER trước lưu profile; không tin role client.
     * @param adminToken JWT service account IAM
     * @param keycloakId UUID user vừa tạo
     * @return tín hiệu gán role hoặc lỗi để caller bù trừ
     */
    private Mono<Void> assignDefaultBuyer(final String adminToken, final String
        keycloakId) {
        String adminBase = baseUrl + "/admin/realms/" + realm;
        return webClient.get().uri(adminBase + "/roles/ROLE_BUYER")
            .headers(headers -> headers.setBearerAuth(adminToken))
            .retrieve().bodyToMono(Map.class).timeout(REQUEST_TIMEOUT)
            .flatMap(role -> webClient.post().uri(adminBase + "/users/"
                + keycloakId + "/role-mappings/realm")
                .headers(headers -> headers.setBearerAuth(adminToken))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(List.of(
                    role))
                .retrieve().bodyToMono(Void.class).timeout(REQUEST_TIMEOUT));
    }

    /**
     * Chỉ chuyển field hồ sơ được phép sang user-service.
     * Giữ chuẩn hóa email/tên cũ, không chuyển tiếp password.
     * @param body JSON đăng ký với field hồ sơ tùy chọn
     * @param email email đã kiểm tra
     * @param fullName tên hồ sơ hoặc email làm dự phòng
     * @return payload hồ sơ không chứa credential IAM
     */
    private Map<String, Object> buildProfilePayload(final Map<String, Object>
        body, final String email, final String fullName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", email.trim().toLowerCase());
        payload.put("fullName", fullName != null ? fullName.trim() : email);

        if (body.get("phone") != null) {
            payload.put("phone", body.get("phone"));
        }
        if (body.get("identifyNumber") != null) {
            payload.put("identifyNumber", body.get("identifyNumber"));
        }
        if (body.get("gender") != null) {
            payload.put("gender", body.get("gender"));
        }
        if (body.get("dateOfBirth") != null) {
            payload.put("dateOfBirth", body.get("dateOfBirth"));
        }
        if (body.get("address") != null) {
            payload.put("address", body.get("address"));
        }

        return payload;
    }

    /**
     * Lưu profile sau khi IAM đã tạo user/gán role.
     * Lỗi profile được giữ nguyên sau cleanup; lỗi cleanup chỉ ghi log.
     * @param adminToken JWT service account gọi profile/cleanup IAM
     * @param keycloakId UUID user IAM để liên kết hồ sơ
     * @param payload field hồ sơ đã lọc, không có password
     * @return envelope hồ sơ 201 hoặc lỗi từ user-service
     */
    private Mono<ResponseEntity<ApiResponse<Object>>> createProfile(
            final String adminToken, final String keycloakId,
            final Map<String, Object> payload) {
        return webClient.post()
            .uri(userServiceUrl + "/api/v1/users")
            .headers(headers -> headers.setBearerAuth(adminToken))
            .header("X-Profile-Subject", keycloakId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(Map.class)
            .timeout(REQUEST_TIMEOUT)
            .map(profile -> {
                // Giữ tương thích DTO thô và envelope của user-service.
                Object data = profile.containsKey("data")
                        ? profile.get("data") : profile;
                return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(data));
            })
            .onErrorResume(error -> {
                LOG.error("Lỗi khi tạo hồ sơ tại user-service, tiến hành rollb"
                    + "ack Keycloak user: {}",
                    keycloakId, error);
                return rollbackKeycloakUser(adminToken, keycloakId)
                    .then(Mono.error(error));
            });
    }
}
