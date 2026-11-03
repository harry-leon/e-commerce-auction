package com.nexus.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;

/** Bảo vệ chuyển cookie sang Bearer và chặn CSRF; không kết nối Keycloak thật. */
class CookieAuthenticationFilterTest {
    private final CookieAuthenticationFilter filter = new CookieAuthenticationFilter();

    /** Request cookie thay đổi dữ liệu thiếu custom header phải dừng trước downstream. */
    @Test
    void rejectsCookieMutationWithoutCsrfHeader() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/cart")
            .cookie(new HttpCookie("nexus_access", "fixture")));
        filter.filter(exchange, next -> { fail("Must not forward CSRF request");
            return Mono.empty(); }).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }

    /** JWT lấy từ cookie được chuyển cho security/service; auth bỏ qua access hết hạn. */
    @Test
    void forwardsBearerButKeepsRefreshIndependentOfAccess() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me")
            .cookie(new HttpCookie("nexus_access", "fixture")));
        filter.filter(exchange, next -> {
            assertEquals("Bearer fixture", next.getRequest().getHeaders().getFirst("Authorization"));
            return Mono.empty();
        }).block();
        var refresh = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/refresh")
            .header("X-Nexus-Request", "1")
            .cookie(new HttpCookie("nexus_access", "expired")));
        filter.filter(refresh, next -> {
            assertNull(next.getRequest().getHeaders().getFirst("Authorization"));
            return Mono.empty();
        }).block();
    }

    /** Login cũng chặn CSRF khi chưa có cookie; logout xóa đủ hai cookie đúng path. */
    @Test
    void rejectsLoginWithoutHeaderAndClearsLogoutCookies() {
        var login = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login"));
        filter.filter(login, next -> { fail("Login requires CSRF header");
            return Mono.empty(); }).block();
        assertEquals(HttpStatus.FORBIDDEN, login.getResponse().getStatusCode());
        var controller = new com.nexus.gateway.controller.AuthGatewayController(
            org.springframework.web.reactive.function.client.WebClient.builder());
        var response = controller.logout();
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        assertEquals(2, response.getHeaders().get("Set-Cookie").size());
        assertTrue(response.getHeaders().get("Set-Cookie").stream()
            .allMatch(value -> value.contains("Max-Age=0") && value.contains("HttpOnly")));
    }
}
