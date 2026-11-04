package com.nexus.gateway.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Bảo vệ điều phối IAM/profile sau khi tách helper để sửa Checkstyle.
 * HTTP fixture không kết nối IAM thật; token/secret chỉ là dữ liệu test.
 */
class AuthOrchestrationTest {
    private final List<String> calls = new ArrayList<>();
    private AuthGatewayController controller;
    private boolean profileFailure;
    private boolean roleFailure;
    private boolean cleanupFailure;
    private boolean profileReadFailure;
    private HttpStatus profileErrorStatus = HttpStatus.INTERNAL_SERVER_ERROR;

    @BeforeEach
    void setup() {
        controller = new AuthGatewayController(WebClient.builder()
                .exchangeFunction(this::respond));
        ReflectionTestUtils.setField(controller, "tokenUri",
                "https://iam.example.test/token");
        ReflectionTestUtils.setField(controller, "clientId", "test-web");
        ReflectionTestUtils.setField(controller, "adminClientId", "test-cli");
        ReflectionTestUtils.setField(controller, "adminClientSecret",
                "test-only-not-a-credential");
        ReflectionTestUtils.setField(controller, "baseUrl",
                "https://iam.example.test");
        ReflectionTestUtils.setField(controller, "realm", "test");
        ReflectionTestUtils.setField(controller, "userServiceUrl",
                "https://profile.example.test");
    }

    /** Tạo IAM → gán BUYER → lưu profile; thành công không được cleanup user. */
    @Test
    void registrationCreatesProfileAfterAssigningBuyerRole() {
        var response = controller.register(registration()).block();
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().success());
        assertEquals(List.of(
                "POST /token",
                "POST /admin/realms/test/users",
                "GET /admin/realms/test/roles/ROLE_BUYER",
                "POST /admin/realms/test/users/user-test/role-mappings/realm",
                "POST /api/v1/users"), calls);
    }

    /** Lỗi lưu profile phải xóa IAM user vừa tạo và giữ phản hồi lỗi. */
    @Test
    void profileFailureRollsBackCreatedIamUser() {
        profileFailure = true;
        var response = controller.register(registration()).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode());
        assertFalse(response.getBody().success());
        assertEquals("DELETE /admin/realms/test/users/user-test",
                calls.getLast());
        assertEquals(1, calls.stream()
                .filter(call -> call.startsWith("DELETE ")).count());
    }

    /** Gán role lỗi phải cleanup IAM trước khi có bất kỳ profile nào. */
    @Test
    void roleFailureRollsBackWithoutCreatingProfile() {
        roleFailure = true;
        var response = controller.register(registration()).block();
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                response.getStatusCode());
        assertFalse(calls.contains("POST /api/v1/users"));
        assertEquals("DELETE /admin/realms/test/users/user-test",
                calls.getLast());
    }

    /** Login/refresh chỉ trả profile; token nằm trong Set-Cookie HttpOnly, Strict và Secure. */
    @Test
    void loginAndRefreshPreserveTokenAndProfileEnvelope() {
        var login = controller.login(Map.of("email", "buyer@example.test",
                "password", "test-only-password")).block();
        assertTrue(login.getBody().success());
        assertFalse(login.getBody().data().containsKey("accessToken"));
        assertFalse(login.getBody().data().containsKey("refreshToken"));
        var cookies = login.getHeaders().get("Set-Cookie");
        assertEquals(2, cookies.size());
        assertTrue(cookies.stream().allMatch(value -> value.contains("HttpOnly")
            && value.contains("SameSite=Strict") && value.contains("Secure")));
        assertEquals(Map.of("id", "profile-test"),
                login.getBody().data().get("user"));
        var refresh = controller.refreshToken(
                "fixture-refresh").block();
        assertEquals(login.getBody().data(), refresh.getBody().data());
        assertEquals(List.of("POST /token", "GET /api/v1/users/me",
                "POST /token", "GET /api/v1/users/me"), calls);
    }

    /** Cleanup IAM lỗi không được che mất lỗi profile gốc hoặc gọi xóa lặp. */
    @Test
    void cleanupFailurePreservesOriginalProfileError() {
        profileFailure = true;
        cleanupFailure = true;
        profileErrorStatus = HttpStatus.SERVICE_UNAVAILABLE;
        var response = controller.register(registration()).block();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                response.getStatusCode());
        assertFalse(response.getBody().success());
        assertEquals(1, calls.stream()
                .filter(call -> call.startsWith("DELETE ")).count());
    }

    /** IAM cấp token nhưng profile lỗi thì login/refresh không tạo session giả. */
    @Test
    void missingProfilePreventsSuccessfulLoginAndRefresh() {
        profileReadFailure = true;
        var login = controller.login(Map.of("email", "buyer@example.test",
                "password", "test-only-password")).block();
        var refresh = controller.refreshToken(
                "fixture-refresh").block();
        assertFalse(login.getBody().success());
        assertFalse(refresh.getBody().success());
        assertNull(login.getBody().data());
        assertNull(refresh.getBody().data());
        assertEquals(List.of("POST /token", "GET /api/v1/users/me",
                "POST /token", "GET /api/v1/users/me"), calls);
    }

    /** Payload hợp lệ để test điều phối IAM/profile thay vì nhánh validation. */
    private Map<String, Object> registration() {
        return Map.of("email", "buyer@example.test", "password", "Nexus12@",
                "fullName", "Test Buyer");
    }

    /** Fixture route ghi thứ tự gọi và kiểm tra JWT/subject liên kết profile. */
    private Mono<ClientResponse> respond(final ClientRequest request) {
        String path = request.url().getPath();
        calls.add(request.method().name() + " " + path);
        if (path.equals("/token")) {
            return json(HttpStatus.OK, """
                    {"access_token":"fixture-access",
                     "refresh_token":"fixture-refresh","expires_in":300}
                    """);
        }
        if (path.equals("/admin/realms/test/users")) {
            return Mono.just(ClientResponse.create(HttpStatus.CREATED)
                    .header("Location", "https://iam.example.test"
                            + "/admin/realms/test/users/user-test").build());
        }
        if (path.endsWith("/roles/ROLE_BUYER")) {
            return json(HttpStatus.OK,
                    "{\"id\":\"role-test\",\"name\":\"ROLE_BUYER\"}");
        }
        if (path.endsWith("/role-mappings/realm")) {
            return roleFailure ? json(HttpStatus.INTERNAL_SERVER_ERROR, "{}")
                    : Mono.just(ClientResponse
                            .create(HttpStatus.NO_CONTENT).build());
        }
        if (path.equals("/api/v1/users")) {
            assertEquals("Bearer fixture-access",
                    request.headers().getFirst("Authorization"));
            assertEquals("user-test",
                    request.headers().getFirst("X-Profile-Subject"));
            return profileFailure
                    ? json(profileErrorStatus, "{}")
                    : json(HttpStatus.CREATED,
                            "{\"data\":{\"id\":\"profile-test\"}}");
        }
        if (path.equals("/api/v1/users/me")) {
            if (profileReadFailure) {
                return json(HttpStatus.SERVICE_UNAVAILABLE, "{}");
            }
            return json(HttpStatus.OK,
                    "{\"data\":{\"id\":\"profile-test\"}}");
        }
        if (path.endsWith("/users/user-test")
                && request.method().name().equals("DELETE")) {
            if (cleanupFailure) {
                return json(HttpStatus.BAD_GATEWAY, "{}");
            }
            return Mono.just(ClientResponse
                    .create(HttpStatus.NO_CONTENT).build());
        }
        throw new AssertionError("Unexpected fixture route: " + path);
    }

    /** Tạo response JSON của HTTP fixture, không gọi hệ thống bên ngoài. */
    private Mono<ClientResponse> json(
            final HttpStatus status, final String body) {
        return Mono.just(ClientResponse.create(status)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(body).build());
    }
}
