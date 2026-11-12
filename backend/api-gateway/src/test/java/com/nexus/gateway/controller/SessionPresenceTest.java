package com.nexus.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Probe khách trả 200, chỉ tiết lộ có cookie; tuyệt đối không gọi IAM hoặc trả token. */
class SessionPresenceTest {
    @Test
    void reportsCookiePresenceWithoutAuthenticatingOrCaching() {
        var controller = new AuthGatewayController(WebClient.builder().exchangeFunction(request -> {
            throw new AssertionError("Probe không được gọi IAM");
        }));
        var client = WebTestClient.bindToController(controller).build();
        client.get().uri("/api/v1/auth/session").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectBody().json("{\"hasSessionCookies\":false}");
        client.get().uri("/api/v1/auth/session").cookie("nexus_refresh", "opaque")
                .exchange().expectStatus().isOk()
                .expectBody().json("{\"hasSessionCookies\":true}");
        client.get().uri("/api/v1/auth/session").cookie("nexus_access", "opaque")
                .exchange().expectStatus().isOk()
                .expectBody().json("{\"hasSessionCookies\":true}");
    }
}
