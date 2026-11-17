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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    /** Mapper phân tích JSON từ downstream error payload; tái sử dụng an toàn đa luồng. */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * Kiểm tra tính khả dụng của email trước khi tiếp tục các bước đăng ký tiếp theo.
     *
     * 1. Chức năng: Kiểm tra email người dùng nhập đã tồn tại trong Keycloak IAM hay chưa.
     * 2. Đầu vào và kết quả:
     *    - Đầu vào: Tham số rawEmail từ query param (ví dụ: ?email=buyer@nexus.vn).
     *    - Kết quả: ApiResponse chứa { "available": boolean, "email": string }.
     * 3. Luồng xử lý: Chuẩn hóa email về chữ thường → gọi Keycloak Admin token → truy vấn Admin REST API
     *    tìm kiếm chính xác người dùng theo email → trả về available = true nếu danh sách rỗng, false nếu đã có.
     * 4. Quy tắc và lý do: Ngăn chặn tình trạng người dùng điền hết Bước 2 và Bước 3 rồi mới bị chặn trùng email.
     *    Không cache kết quả để đảm bảo thông tin tức thời sau khi có tài khoản mới đăng ký.
     * 5. Lỗi và giới hạn: Nếu Keycloak lỗi mạng hoặc gián đoạn, fallback trả về available = true để không chặn
     *    tiến trình đăng ký của người dùng; validation cuối cùng vẫn được Keycloak kiểm soát an toàn.
     */
    @GetMapping("/check-email")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> checkEmail(
            @RequestParam("email") final String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank() || !rawEmail.contains("@")) {
            return Mono.just(ResponseEntity.badRequest().body(
                ApiResponse.error("INVALID_ARGUMENT", "Email không đúng định dạng")
            ));
        }
        String normalizedEmail = rawEmail.trim().toLowerCase(java.util.Locale.ROOT);
        return fetchKeycloakAdminToken()
            .flatMap(adminToken -> {
                String uri = baseUrl + "/admin/realms/" + realm + "/users?email=" + normalizedEmail + "&exact=true";
                return webClient.get()
                    .uri(uri)
                    .header("Authorization", "Bearer " + adminToken)
                    .retrieve()
                    .bodyToMono(List.class)
                    .timeout(REQUEST_TIMEOUT)
                    .map(users -> {
                        boolean available = users.isEmpty();
                        Map<String, Object> result = new HashMap<>();
                        result.put("available", available);
                        result.put("email", normalizedEmail);
                        return ResponseEntity.ok(ApiResponse.ok(result));
                    });
            })
            .onErrorResume(ex -> {
                LOG.warn("Lỗi khi kiểm tra email tại Keycloak: {}", ex.getMessage());
                Map<String, Object> fallback = new HashMap<>();
                fallback.put("available", true);
                fallback.put("email", normalizedEmail);
                return Mono.just(ResponseEntity.ok(ApiResponse.ok(fallback)));
            });
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

        if (body.get("encryptedPassword") != null && !body.get("encryptedPassword").isBlank() && password == null) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR", "Khóa bảo mật đã hết hạn. Vui lòng tải lại trang (F5) và thử lại.",
                    List.of(Map.of("field", "password", "code", "ENCRYPTION_EXPIRED",
                        "message", "Khóa bảo mật đã hết hạn. Vui lòng tải lại trang (F5) và thử lại.")))
            ));
        }

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

        // Trường hợp Frontend gửi encryptedPassword nhưng giải mã thất bại do Gateway vừa khởi động lại đổi RSA key
        if (encryptedPwdObj instanceof String s && !s.isBlank() && password == null) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR", "Khóa bảo mật RSA đã hết hạn. Vui lòng tải lại trang (F5) và thử lại.",
                    List.of(Map.of("field", "password", "code", "ENCRYPTION_EXPIRED",
                        "message", "Khóa bảo mật đã hết hạn do Gateway vừa cập nhật. Vui lòng tải lại trang (F5) và thử lại.")))
            ));
        }

        String fullName = body.get("fullName") instanceof String value
            ? value.trim() : null;

        if (email == null || email.isBlank() || !email.contains("@")
            || fullName == null || fullName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(
                ApiResponse.error("INVALID_ARGUMENT",
                    "Email hợp lệ và họ tên không được để trống")
            ));
        }

        // Validate họ và tên: chỉ chứa chữ cái Unicode (có dấu tiếng Việt) và khoảng trắng, không chứa số/ký tự đặc biệt;
        // bắt buộc gồm cả họ và tên (ít nhất 2 từ) để Keycloak có thể trích xuất firstName và lastName an toàn.
        String trimmedFullName = fullName.trim();
        String[] nameParts = trimmedFullName.split("\\s+");
        if (!trimmedFullName.matches("^[\\p{L}\\s']{2,100}$") || nameParts.length < 2) {
            return Mono.just(ResponseEntity.unprocessableEntity().body(
                ApiResponse.error("VALIDATION_ERROR", "Họ và tên không hợp lệ",
                    List.of(Map.of("field", "fullName", "code", "PATTERN",
                        "message", "Họ và tên phải bao gồm cả họ và tên (ít nhất 2 từ), không chứa số hay ký tự đặc biệt")))
            ));
        }

        // Validate ngày sinh (nếu có): phải theo đúng định dạng chuẩn ISO YYYY-MM-DD,
        // ngày phải tồn tại trong lịch và bắt buộc phải ở trong quá khứ (không nhận ngày tương lai).
        Object dobObj = body.get("dateOfBirth");
        if (dobObj instanceof String dobStr && !dobStr.isBlank()) {
            if (!dobStr.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
                return Mono.just(ResponseEntity.unprocessableEntity().body(
                    ApiResponse.error("VALIDATION_ERROR", "Ngày sinh không hợp lệ",
                        List.of(Map.of("field", "dateOfBirth", "code", "PATTERN",
                            "message", "Ngày sinh phải theo định dạng YYYY-MM-DD (Ví dụ: 2000-01-15)")))
                ));
            }
            try {
                java.time.LocalDate dob = java.time.LocalDate.parse(dobStr);
                java.time.LocalDate today = java.time.LocalDate.now();
                if (!dob.isBefore(today)) {
                    return Mono.just(ResponseEntity.unprocessableEntity().body(
                        ApiResponse.error("VALIDATION_ERROR", "Ngày sinh không hợp lệ",
                            List.of(Map.of("field", "dateOfBirth", "code", "PAST_DATE",
                                "message", "Ngày sinh phải ở trong quá khứ, không được là ngày hiện tại hoặc tương lai")))
                    ));
                }
                if (dob.isBefore(today.minusYears(120))) {
                    return Mono.just(ResponseEntity.unprocessableEntity().body(
                        ApiResponse.error("VALIDATION_ERROR", "Ngày sinh không hợp lệ",
                            List.of(Map.of("field", "dateOfBirth", "code", "MIN_AGE",
                                "message", "Năm sinh không hợp lệ (không vượt quá 120 tuổi)")))
                    ));
                }
            } catch (java.time.format.DateTimeParseException ex) {
                return Mono.just(ResponseEntity.unprocessableEntity().body(
                    ApiResponse.error("VALIDATION_ERROR", "Ngày sinh không hợp lệ",
                        List.of(Map.of("field", "dateOfBirth", "code", "INVALID_DATE",
                            "message", "Ngày sinh không hợp lệ hoặc không tồn tại trong lịch")))
                ));
            }
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
                HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
                int statusCode = ex.getStatusCode().value();
                String message = "Không thể hoàn tất đăng ký";
                Object errorDetails = null;

                if (status == HttpStatus.CONFLICT || statusCode == HttpStatus.CONFLICT.value()) {
                    message = "Email đã được đăng ký trong hệ thống";
                } else {
                    /**
                     * Chức năng: Bóc tách thông điệp và danh sách chi tiết lỗi từ downstream (User Service hoặc Keycloak).
                     * Đầu vào: ex.getResponseBodyAsString() dưới dạng chuỗi JSON.
                     * Kết quả: Trích xuất message và details để trả về cho Frontend hiển thị cạnh đúng ô nhập liệu.
                     * Luồng xử lý: Đọc cấu trúc ApiResponse chuẩn { "error": { "message": "...", "details": [...] } }
                     *              hoặc error body chuẩn của Spring Web / Keycloak.
                     * Quy tắc & lý do: Tránh nuốt lỗi thành câu thông báo chung chung, giúp người dùng biết rõ trường bị từ chối.
                     */
                    try {
                        String responseBody = ex.getResponseBodyAsString();
                        if (responseBody != null && !responseBody.isBlank()) {
                            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
                            if (root.has("error") && root.get("error").isObject()) {
                                JsonNode errNode = root.get("error");
                                if (errNode.has("message") && !errNode.get("message").asText().isBlank()) {
                                    message = errNode.get("message").asText();
                                }
                                if (errNode.has("details") && !errNode.get("details").isNull()) {
                                    errorDetails = OBJECT_MAPPER.convertValue(errNode.get("details"), Object.class);
                                }
                            } else if (root.has("message") && !root.get("message").asText().isBlank()) {
                                message = root.get("message").asText();
                            } else if (root.has("errorMessage") && !root.get("errorMessage").asText().isBlank()) {
                                message = root.get("errorMessage").asText();
                            }
                        }
                    } catch (Exception parseEx) {
                        LOG.debug("Không thể bóc tách response body lỗi từ downstream: {}", parseEx.getMessage());
                    }
                }

                HttpStatus returnStatus = status != null ? status : HttpStatus.BAD_REQUEST;
                return Mono.just(ResponseEntity.status(returnStatus).body(
                    errorDetails != null
                        ? ApiResponse.error("REGISTRATION_FAILED", message, errorDetails)
                        : ApiResponse.error("REGISTRATION_FAILED", message)
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
     * Trích xuất các trường định danh cơ bản cần thiết cho phiên đăng nhập/refresh (Auth context),
     * loại bỏ các trường nhạy cảm (CCCD/identifyNumber) và các trường chi tiết hồ sơ (địa chỉ,
     * ngày sinh, giới tính, thời gian tạo/sửa, danh sách quyền privileges chi tiết).
     *
     * 1. Chức năng: Tối giản hóa payload user trong response login/refresh, ngăn chặn rò rỉ PII và giảm tải payload.
     * 2. Đầu vào và kết quả:
     *    - Đầu vào: Object profile thô (Map) nhận từ User Service (/api/v1/users/me).
     *    - Kết quả: Map chỉ chứa các thông tin định danh và phân quyền cơ bản: id, email, fullName, phone, roles, status.
     * 3. Luồng xử lý: Bóc tách object profile (hoặc trường 'data' nếu bọc envelope) → trích lọc các trường an toàn.
     * 4. Quy tắc và lý do: Bảo vệ quyền riêng tư người dùng (không trả CCCD ở auth response);
     *    thông tin chi tiết đầy đủ vẫn được phục vụ riêng qua GET /api/v1/users/me khi truy cập trang Profile.
     */
    private Map<String, Object> sanitizeUserForAuth(final Object rawUser) {
        if (!(rawUser instanceof Map<?, ?> userMap)) {
            return Map.of();
        }
        Map<String, Object> cleanUser = new HashMap<>();
        if (userMap.get("id") != null) {
            cleanUser.put("id", userMap.get("id"));
        }
        if (userMap.get("email") != null) {
            cleanUser.put("email", userMap.get("email"));
        }
        if (userMap.get("fullName") != null) {
            cleanUser.put("fullName", userMap.get("fullName"));
        }
        if (userMap.get("phone") != null) {
            cleanUser.put("phone", userMap.get("phone"));
        }
        if (userMap.get("status") != null) {
            cleanUser.put("status", userMap.get("status"));
        }
        if (userMap.get("roles") != null) {
            cleanUser.put("roles", userMap.get("roles"));
        }
        return cleanUser;
    }

    /**
     * Đọc profile bằng JWT mới và bọc contract frontend.
     * Hỗ trợ DTO thô hoặc envelope; không tự tạo profile giả.
     * Token chỉ đặt trong cookie HttpOnly sau khi đọc profile thành công.
     * Payload user được lọc tối giản (loại bỏ CCCD, địa chỉ, ngày sinh) để đảm bảo an toàn.
     *
     * @param tokens phản hồi token của Keycloak
     * @return envelope hồ sơ và hạn token; lỗi profile được truyền lên
     */
    private Mono<ResponseEntity<ApiResponse<Map<String, Object>>>>
        withProfile(final Map tokens) {
        String accessToken = (String) tokens.get("access_token");
        return webClient.get().uri(userServiceUrl + "/api/v1/users/me")
            .headers(headers -> headers.setBearerAuth(accessToken))
            .retrieve().bodyToMono(Map.class).timeout(REQUEST_TIMEOUT)
            .map(profile -> {
                Object rawUser = profile.containsKey("data")
                    ? profile.get("data") : profile;
                Map<String, Object> result = new HashMap<>();
                result.put("expiresIn", tokens.get("expires_in"));
                result.put("tokenType", "Bearer");
                result.put("user", sanitizeUserForAuth(rawUser));
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
     * Tối giản hóa payload phản hồi đăng ký thành công.
     * Chỉ trả về thông tin định danh tối thiểu (id, email, fullName),
     * loại bỏ CCCD (identifyNumber), địa chỉ, ngày sinh, giới tính, trạng thái
     * và danh sách quyền (privileges) không cần thiết khi người dùng chưa đăng nhập.
     *
     * 1. Chức năng: Ngăn chặn rò rỉ dữ liệu cá nhân nhạy cảm (PII) và giảm kích thước response đăng ký.
     * 2. Đầu vào: Object profile thô từ user-service (/api/v1/users).
     * 3. Kết quả: Map chỉ chứa thông tin xác nhận tối thiểu: id, email, fullName.
     * 4. Quy tắc: Người dùng mới tạo chưa có phiên xác thực, không trả về token hay quyền hạn.
     */
    private Map<String, Object> sanitizeUserForRegistration(final Object rawUser) {
        if (!(rawUser instanceof Map<?, ?> userMap)) {
            return Map.of();
        }
        Map<String, Object> cleanUser = new HashMap<>();
        if (userMap.get("id") != null) {
            cleanUser.put("id", userMap.get("id"));
        }
        if (userMap.get("email") != null) {
            cleanUser.put("email", userMap.get("email"));
        }
        if (userMap.get("fullName") != null) {
            cleanUser.put("fullName", userMap.get("fullName"));
        }
        return cleanUser;
    }

    /**
     * Lưu profile sau khi IAM đã tạo user/gán role.
     * Lỗi profile được giữ nguyên sau cleanup; lỗi cleanup chỉ ghi log.
     * Payload phản hồi được lọc tối giản (chỉ giữ id, email, fullName) để bảo mật PII.
     *
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
                // Giữ tương thích DTO thô và envelope của user-service,
                // chỉ trả về thông tin định danh tối thiểu (id, email, fullName).
                Object rawUser = profile.containsKey("data")
                        ? profile.get("data") : profile;
                Object cleanData = sanitizeUserForRegistration(rawUser);
                return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(cleanData));
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
