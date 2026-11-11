package com.nexus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** Netty Gateway thật proxy upgrade/frame của Auction và Notification; fixture không thay test JWT/STOMP tại service. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
@DirtiesContext
class GatewayWebSocketTest {
    private static final AtomicReference<String> FORGED_USER = new AtomicReference<>();
    private static final DisposableServer UPSTREAM = HttpServer.create().host("127.0.0.1").port(0)
            .route(routes -> routes.ws("/ws/{channel}", (in, out) -> {
                FORGED_USER.set(in.headers().get("X-User-Id"));
                return out.sendString(in.receive().asString().map(s -> "upstream:" + s));
            })).bindNow();
    @LocalServerPort private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AUCTION_WS_URL", () -> "ws://127.0.0.1:" + UPSTREAM.port());
        registry.add("NOTIFICATION_WS_URL", () -> "ws://127.0.0.1:" + UPSTREAM.port());
    }

    @AfterAll
    static void cleanup() { UPSTREAM.disposeNow(); }

    /** Cả hai route phải proxy HTTP upgrade/frame và loại header định danh giả trước upstream. */
    @ParameterizedTest
    @ValueSource(strings = {"auctions", "notifications"})
    void upgradesThroughGatewayAndStripsForgedIdentity(String channel) {
        var received = new AtomicReference<String>();
        var headers = new org.springframework.http.HttpHeaders();
        headers.set("X-User-Id", "forged");
        headers.setOrigin("http://localhost:3000");
        new ReactorNettyWebSocketClient().execute(URI.create("ws://127.0.0.1:" + port + "/ws/" + channel), headers,
                session -> session.send(reactor.core.publisher.Mono.just(session.textMessage("CONNECT")))
                    .then(session.receive().next().doOnNext(m -> received.set(m.getPayloadAsText())).then()))
                .block(Duration.ofSeconds(10));
        assertEquals("upstream:CONNECT", received.get());
        assertNull(FORGED_USER.get());
    }

    /** Preflight mở đúng origin/header; origin lạ không được dùng credential HTTP của browser. */
    @Test
    void restrictsCorsOriginsAndKeepsHttpProtected() {
        var client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
        client.options().uri("/api/v1/orders").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Nexus-Request,Content-Type")
                .exchange().expectStatus().isOk().expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
        client.options().uri("/api/v1/orders").header("Origin", "https://evil.test")
                .header("Access-Control-Request-Method", "POST").exchange().expectStatus().isForbidden();
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
    }
}
