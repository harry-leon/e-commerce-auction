package com.nexus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Khởi động toàn bộ Gateway/Netty trên cổng ngẫu nhiên, dùng HTTP thật.
 * Chỉ downstream Commerce là fixture; không gọi Keycloak/service ngoài.
 * Bảo vệ boot cấu hình Spring, whitelist và query IPN sau khi format code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.config.enabled=false",
                "eureka.client.enabled=false",
                "app.keycloak.jwk-set-uri=https://example.test/jwks",
                "app.keycloak.issuer=https://example.test/realm"
        })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GatewayRuntimeTest {
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final AtomicReference<String> QUERY = new AtomicReference<>();
    private static final AtomicReference<String> USER = new AtomicReference<>();
    private static final HttpServer DOWNSTREAM = downstream();

    @LocalServerPort
    private int port;

    /** Cấp URL fixture trước khi Spring bind route, không dùng DB/.env team. */
    @DynamicPropertySource
    static void configure(final DynamicPropertyRegistry registry) {
        registry.add("COMMERCE_SERVICE_URL", () -> "http://127.0.0.1:"
                + DOWNSTREAM.getAddress().getPort());
    }

    @AfterAll
    static void cleanup() {
        DOWNSTREAM.stop(0);
    }

    /**
     * Netty thật phải UP; chỉ GET IPN đi qua proxy khi thiếu JWT.
     * Query đã encode cần giữ nguyên vì đổi encoding có thể làm sai HMAC.
     */
    @Test
    void bootsAndPreservesIpnQueryWhileRejectingProtectedRequests() {
        String base = "http://127.0.0.1:" + port;
        var client = WebTestClient.bindToServer().baseUrl(base).build();
        client.get().uri("/actuator/health").exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.status").isEqualTo("UP");

        String raw = "vnp_OrderInfo=Thanh+toan%2B%26"
                + "&vnp_Amount=5000000&vnp_SecureHash=fixture";
        client.get().uri(URI.create(base
                        + "/api/v1/payments/vnpay/ipn?" + raw))
                .header("X-User-Id", "forged")
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.RspCode").isEqualTo("97");
        assertEquals(raw, QUERY.get());
        assertNull(USER.get());

        client.get().uri("/api/v1/payments/private-payment").exchange()
                .expectStatus().isUnauthorized();
        client.post().uri("/api/v1/payments/vnpay/ipn").exchange()
                .expectStatus().isUnauthorized();
        assertEquals(1, CALLS.get());
    }

    /** Fixture lưu raw query/header để kiểm tra proxy, không giả kết quả paid. */
    private static HttpServer downstream() {
        try {
            var server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/payments/vnpay/ipn", exchange -> {
                CALLS.incrementAndGet();
                QUERY.set(exchange.getRequestURI().getRawQuery());
                USER.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                byte[] body = "{\"RspCode\":\"97\",\"Message\":\"Fixture\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                        "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Không tạo được fixture HTTP", error);
        }
    }
}
