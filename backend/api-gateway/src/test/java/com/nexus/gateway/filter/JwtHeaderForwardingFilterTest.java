package com.nexus.gateway.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** Hồi quy forward hai lần với Mono<Void> và giả mạo header định danh trên route public/authenticated. */
class JwtHeaderForwardingFilterTest {
    @Test
    void anonymousRequestIsSanitizedAndForwardedOnce() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/products")
                .header("X-User-Id", "forged").header("X-User-Roles", "ROLE_ADMIN").header("X-Profile-Subject", "forged").build());
        var count = new AtomicInteger();
        new JwtHeaderForwardingFilter().filter(exchange, next -> {
            count.incrementAndGet();
            assertNull(next.getRequest().getHeaders().getFirst("X-User-Id"));
            assertNull(next.getRequest().getHeaders().getFirst("X-User-Roles"));
            assertNull(next.getRequest().getHeaders().getFirst("X-Profile-Subject"));
            return Mono.empty();
        }).block();
        assertEquals(1, count.get());
    }

    @Test
    void verifiedSubjectReplacesUntrustedHeaderExactlyOnce() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/users/me").header("X-User-Id", "forged").build());
        var count = new AtomicInteger();
        var jwt = Jwt.withTokenValue("token").header("alg", "RS256").subject("real-subject").claim("email", "real@example.com").build();
        new JwtHeaderForwardingFilter().filter(exchange, next -> {
            count.incrementAndGet();
            assertEquals("real-subject", next.getRequest().getHeaders().getFirst("X-User-Id"));
            assertEquals("real@example.com", next.getRequest().getHeaders().getFirst("X-User-Email"));
            return Mono.empty();
        }).contextWrite(ReactiveSecurityContextHolder.withAuthentication(new JwtAuthenticationToken(jwt))).block();
        assertEquals(1, count.get());
    }
}
