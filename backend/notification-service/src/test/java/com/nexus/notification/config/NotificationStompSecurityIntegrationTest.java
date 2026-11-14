package com.nexus.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.UserDestinationResolver;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;

/**
 * Chạy HTTP upgrade/STOMP/broker và SecurityConfig/JwtDecoder production với RSA/JWKS loopback thật.
 * JWKS/issuer là fixture độc lập, không phải Keycloak triển khai; không quét nghiệp vụ/DB hoặc gửi email.
 * Bảo vệ quyền kênh, reconnect và lifecycle; không coi simple broker là hàng đợi push bền vững.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationStompSecurityIntegrationTest {
    private static final String ORIGIN = "https://notification-ui.example.test";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private HttpServer jwks;
    private RSAKey key;
    private String issuer;
    private URI endpoint;
    private ServletWebServerApplicationContext context;

    /** Chỉ import transport/security thật; loại datasource/Flyway để fixture không thể chạm DB nhóm. */
    @TestConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import({SecurityConfig.class, NotificationWebSocketConfig.class, NotificationStompInterceptor.class,
            ExpiringNotificationWebSocketSessions.class, NotificationTransportHealth.class, NotificationProperties.class})
    static class Config { }

    /** JWKS chỉ công bố public key sinh mới; mọi cổng bind loopback, cấu hình riêng không đọc application.yml/.env. */
    @BeforeAll
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] publicKeys = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        jwks.createContext("/certs", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, publicKeys.length);
                exchange.getResponseBody().write(publicKeys);
            }
        });
        jwks.start();
        issuer = "http://127.0.0.1:" + jwks.getAddress().getPort() + "/fixture-realm";
        var app = new SpringApplication(Config.class);
        app.setWebApplicationType(WebApplicationType.SERVLET);
        context = (ServletWebServerApplicationContext) app.run("--spring.config.name=notification-stomp-isolated",
                "--spring.application.name=notification-stomp-fixture", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.cloud.config.enabled=false", "--eureka.client.enabled=false", "--logging.level.root=WARN",
                "--logging.level.org.springframework=WARN", "--spring.main.banner-mode=off",
                "--notification.email-enabled=false", "--app.web.allowed-origins=" + ORIGIN,
                "--app.keycloak.issuer=" + issuer, "--app.keycloak.jwk-set-uri=http://127.0.0.1:" + jwks.getAddress().getPort() + "/certs");
        endpoint = URI.create("ws://127.0.0.1:" + context.getWebServer().getPort() + "/ws/notifications");
    }

    /** Dừng broker/timer/server fixture kể cả assertion lỗi; socket từng ca được đóng bằng try-with-resources. */
    @AfterAll
    void stop() {
        try { if (context != null) { context.close(); } }
        finally { if (jwks != null) { jwks.stop(0); } client.close(); }
    }

    /** Chữ ký/issuer/expiry phải được decoder thật kiểm tra; Origin lạ bị chặn ngay HTTP upgrade. */
    @Test
    void rejectsInvalidTokensAndForeignOrigin() throws Exception {
        String user = UUID.randomUUID().toString();
        var forged = new RSAKeyGenerator(2048).keyID(key.getKeyID()).generate();
        for (String bearer : new String[]{"", "Bearer malformed", "Bearer " + token(user, forged, issuer, Instant.now().plusSeconds(120)),
                "Bearer " + token(user, key, issuer + "-wrong", Instant.now().plusSeconds(120)),
                "Bearer " + token(user, key, issuer, Instant.now().minusSeconds(120)),
                "Bearer " + token(user, key, issuer, null)}) {
            try (var socket = new Socket(ORIGIN)) {
                socket.connect(bearer);
                String rejected = socket.next();
                assertTrue(rejected.startsWith("ERROR") || rejected.equals("CLOSED"), "CONNECT không hợp lệ không được nhận CONNECTED");
            }
        }
        var failure = assertThrows(CompletionException.class, () -> new Socket("https://other.example.test"));
        assertTrue(failure.getCause() instanceof WebSocketHandshakeException);
        assertEquals(403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
    }

    /** Hai JWT nhận đúng kênh riêng; subscribe topic người khác và SEND giả vào queue đều bị chặn trước broker. */
    @Test
    void isolatesRecipientsAndBlocksClientPublishing() throws Exception {
        String first = UUID.randomUUID().toString(); String second = UUID.randomUUID().toString();
        try (var a = authenticated(first); var b = authenticated(second)) {
            subscribe(a, first); subscribe(b, second);
            broker().convertAndSendToUser(first, "/queue/notifications", "only-first");
            broker().convertAndSendToUser(second, "/queue/notifications", "only-second");
            assertTrue(a.next().contains("only-first"));
            String received = b.next();
            assertTrue(received.contains("only-second")); assertFalse(received.contains("only-first"));
            try (var attacker = authenticated(first)) {
                attacker.send("SUBSCRIBE\nid:foreign\ndestination:/topic/users/" + second + "/notifications\n\n\0");
                String rejected = attacker.next();
                assertTrue(rejected.startsWith("ERROR") || rejected.equals("CLOSED"));
            }
            try (var attacker = authenticated(first)) {
                attacker.send("SEND\ndestination:/topic/users/" + second + "/notifications\n\nforged\0");
                String rejected = attacker.next();
                assertTrue(rejected.startsWith("ERROR") || rejected.equals("CLOSED"));
            }
            // Sentinel được gửi sau ERROR: kênh hợp lệ còn dùng được và không chứa payload giả trước nó.
            broker().convertAndSendToUser(second, "/queue/notifications", "after-denial");
            assertTrue(b.next().contains("after-denial"));
        }
    }

    /** Reconnect JWT mới nhận push mới; tin phát khi offline không tự replay từ broker RAM. Inbox cần resync riêng. */
    @Test
    void reconnectsWithFreshJwtWithoutPretendingDurableReplay() throws Exception {
        String user = UUID.randomUUID().toString();
        try (var first = authenticated(user)) {
            subscribe(first, user);
            broker().convertAndSendToUser(user, "/queue/notifications", "before-disconnect");
            assertTrue(first.next().contains("before-disconnect"));
        }
        await(() -> context.getBean(SimpUserRegistry.class).getUser(user) == null);
        broker().convertAndSendToUser(user, "/queue/notifications", "offline-not-durable");
        try (var second = authenticated(user)) {
            subscribe(second, user);
            broker().convertAndSendToUser(user, "/queue/notifications", "after-reconnect");
            String received = second.next();
            assertTrue(received.contains("after-reconnect")); assertFalse(received.contains("offline-not-durable"));
        }
    }

    /** Dừng/khởi động broker thật phải đổi health theo lifecycle, không phát BrokerAvailabilityEvent giả trong test. */
    @Test
    void healthTracksActualBrokerStopAndStart() throws Exception {
        var handler = context.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class);
        var health = context.getBean(NotificationTransportHealth.class);
        assertEquals(Status.UP, health.health().getStatus());
        try {
            handler.stop();
            assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
            assertEquals(false, health.health().getDetails().get("durablePush"));
        } finally { handler.start(); }
        assertEquals(Status.UP, health.health().getStatus());
        String user = UUID.randomUUID().toString();
        try (var socket = authenticated(user)) {
            subscribe(socket, user);
            broker().convertAndSendToUser(user, "/queue/notifications", "broker-restarted");
            assertTrue(socket.next().contains("broker-restarted"));
        }
    }

    /** Ký RSA cục bộ theo khóa/issuer/expiry của ca test; không dùng credential hoặc JWT từ môi trường. */
    private String token(String user, RSAKey signingKey, String tokenIssuer, Instant expiry) throws Exception {
        var claims = new JWTClaimsSet.Builder().subject(user).issuer(tokenIssuer).issueTime(Date.from(Instant.now().minusSeconds(1)))
                .jwtID(UUID.randomUUID().toString());
        if (expiry != null) { claims.expirationTime(Date.from(expiry)); }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    /** User nhận JWT fixture mới mỗi lần; đóng socket ngay nếu CONNECT/assertion lỗi trước khi trả client. */
    private Socket authenticated(String user) throws Exception {
        var socket = new Socket(ORIGIN);
        try {
            socket.connect("Bearer " + token(user, key, issuer, Instant.now().plusSeconds(120)));
            assertTrue(socket.next().startsWith("CONNECTED"));
            return socket;
        } catch (Throwable error) { socket.close(); throw error; }
    }

    /** Chờ cả user registry và subscription trong broker, tránh race với inbound executor hoặc phát lại tin thử. */
    private void subscribe(Socket socket, String user) throws Exception {
        socket.send("SUBSCRIBE\nid:own\ndestination:/user/queue/notifications\n\n\0");
        await(() -> {
            var principal = context.getBean(SimpUserRegistry.class).getUser(user);
            if (principal == null || principal.getSessions().stream().flatMap(s -> s.getSubscriptions().stream())
                    .noneMatch(s -> "/user/queue/notifications".equals(s.getDestination()))) { return false; }
            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
            headers.setDestination("/user/" + user + "/queue/notifications");
            var target = context.getBean(UserDestinationResolver.class)
                    .resolveDestination(MessageBuilder.withPayload(new byte[0]).setHeaders(headers).build());
            if (target == null || target.getTargetDestinations().isEmpty()) { return false; }
            return target.getTargetDestinations().stream().allMatch(destination -> {
                var routed = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
                routed.setDestination(destination);
                return !context.getBean("simpleBrokerMessageHandler", SimpleBrokerMessageHandler.class).getSubscriptionRegistry()
                        .findSubscriptions(MessageBuilder.withPayload(new byte[0]).setHeaders(routed).build()).isEmpty();
            });
        });
    }

    private SimpMessagingTemplate broker() { return context.getBean(SimpMessagingTemplate.class); }

    /** Poll trạng thái nội bộ tối đa 5 giây, lỗi phải kết thúc test thay vì treo pipeline vô hạn. */
    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(20); }
        assertTrue(condition.getAsBoolean(), "Transport không đạt trạng thái mong đợi trong deadline");
    }

    /** Client STOMP tối thiểu: gom frame phân mảnh, bỏ heartbeat, deadline hữu hạn; không log Authorization/token. */
    private class Socket implements AutoCloseable, WebSocket.Listener {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder fragments = new StringBuilder();
        private final WebSocket socket;

        Socket(String origin) {
            socket = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).header("Origin", origin)
                    .buildAsync(endpoint, this).orTimeout(5, TimeUnit.SECONDS).join();
        }

        /** JWT chỉ đi trong CONNECT frame; không đặt token ở URL hoặc handshake header để tránh thay policy production. */
        void connect(String authorization) {
            send("CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:" + authorization + "\n\n\0");
        }

        void send(String frame) { socket.sendText(frame, true).orTimeout(5, TimeUnit.SECONDS).join(); }

        String next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertTrue(frame != null, "Không nhận được frame/close trong deadline");
            return frame;
        }

        @Override
        public void onOpen(WebSocket ws) { ws.request(1); }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                String text = fragments.toString().replace("\r\n", "\n");
                for (String frame : text.split("\0")) { if (!frame.isBlank()) { frames.add(frame.stripLeading()); } }
                fragments.setLength(0);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            frames.add("CLOSED"); return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) { frames.add("CLOSED"); }

        @Override
        public void close() { socket.abort(); }
    }
}
