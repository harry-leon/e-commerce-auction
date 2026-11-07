package com.nexus.auction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.awaitility.Awaitility.await;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Tomcat/WebSocket/STOMP và JWT RS256/JWKS thật; chỉ repository Auction và IAM HTTP là fixture.
 * Bảo vệ CONNECT sai chữ ký/issuer/expiry, origin, cookie principal và frame giả broadcast.
 * Không yêu cầu DB, realm bên ngoài hoặc bid engine chưa triển khai.
 */
@SpringBootTest(classes = AuctionWebSocketRuntimeTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "spring.cloud.config.enabled=false", "eureka.client.enabled=false",
            "spring.flyway.enabled=false", "spring.main.web-application-type=servlet",
            "logging.level.root=WARN", "app.web.allowed-origins=http://localhost:3000"})
@DirtiesContext
@org.springframework.test.context.ActiveProfiles("ws-runtime")
class AuctionWebSocketRuntimeTest {
    private static final RSAKey KEY = key();
    private static final HttpServer JWKS = jwks();
    private static final String ISSUER = "https://iam.test/realms/nexus-realm";
    @LocalServerPort private int port;
    @MockitoBean private AuctionRepository auctions;
    @Autowired private SimpMessagingTemplate messaging;
    @Autowired private SimpUserRegistry users;

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Profile("ws-runtime")
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class})
    @Import({SecurityConfig.class, AuctionWebSocketConfig.class, AuctionStompInterceptor.class, ExpiringWebSocketSessions.class})
    static class App { }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.keycloak.jwk-set-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
        registry.add("app.keycloak.issuer", () -> ISSUER);
    }

    @AfterAll
    static void cleanup() { JWKS.stop(0); }

    @Test
    void connectsSubscribesAndReceivesServerBroadcast() throws Exception {
        UUID id = UUID.randomUUID();
        var auction = new Auction();
        auction.setVisibility(AuctionVisibility.PUBLIC);
        when(auctions.findById(id)).thenReturn(Optional.of(auction));
        try (var socket = open("http://localhost:3000", null)) {
            socket.send("CONNECT\naccept-version:1.2\nAuthorization:Bearer " + token(KEY, ISSUER, 300, "runtime-buyer") + "\n\n\0");
            assertTrue(socket.next().startsWith("CONNECTED"));
            socket.send("SUBSCRIBE\nid:auction\ndestination:/topic/auctions/" + id + "\n\n\0");
            await().atMost(Duration.ofSeconds(5)).until(() -> users.getUser("runtime-buyer") != null
                    && users.getUser("runtime-buyer").getSessions().stream().anyMatch(s -> !s.getSubscriptions().isEmpty()));
            messaging.convertAndSend("/topic/auctions/" + id, "fixture-event");
            assertTrue(socket.next().contains("fixture-event"));
            socket.send("SEND\ndestination:/topic/auctions/" + id + "\n\nforged\0");
            assertTrue(socket.next().startsWith("ERROR"));
            socket.closed.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsMissingExpiredForgedAndWrongIssuerTokens() throws Exception {
        for (String bearer : List.of("", "Authorization:Bearer " + token(KEY, ISSUER, -120, "expired") + "\n",
                "Authorization:Bearer " + token(key(), ISSUER, 300, "forged") + "\n",
                "Authorization:Bearer " + token(KEY, "https://evil.test", 300, "wrong-issuer") + "\n")) {
            try (var socket = open("http://localhost:3000", null)) {
                socket.send("CONNECT\naccept-version:1.2\n" + bearer + "\n\0");
                assertTrue(socket.next().startsWith("ERROR"));
                socket.closed.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void rejectsUnknownOriginAndClosesIdleExpiredConnection() throws Exception {
        assertThrows(Exception.class, () -> open("https://evil.test", null));
        // Header HTTP mô phỏng bearer Gateway đã đổi từ HttpOnly cookie; CONNECT không cần lộ token ra JS.
        try (var socket = open("http://localhost:3000", token(KEY, ISSUER, 3, "cookie-user"))) {
            socket.send("CONNECT\naccept-version:1.2\n\n\0");
            assertTrue(socket.next().startsWith("CONNECTED"));
            assertEquals(1008, socket.closed.get(8, TimeUnit.SECONDS));
        }
    }

    private Probe open(String origin, String bearer) throws Exception {
        var probe = new Probe();
        var builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                .header("Origin", origin).subprotocols("v12.stomp");
        if (bearer != null) { builder.header("Authorization", "Bearer " + bearer); }
        probe.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/auctions"), probe).get(8, TimeUnit.SECONDS);
        return probe;
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("runtime-test").generate(); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** JWT ký thật; IAM fixture chỉ cấp public JWKS, private key nằm trong tiến trình test. */
    private static String token(RSAKey key, String issuer, long seconds, String subject) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("runtime-test").build(),
                new JWTClaimsSet.Builder().issuer(issuer).subject(subject).issueTime(Date.from(Instant.now().minusSeconds(300)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(seconds)))
                    .claim("realm_access", Map.of("roles", List.of("ROLE_BUYER", "AUCTION.BID"))).build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static HttpServer jwks() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/certs", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    /** Gom fragment WebSocket thành STOMP text; deadline hữu hạn để regression không treo suite. */
    private static class Probe implements WebSocket.Listener, AutoCloseable {
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final StringBuilder buffer = new StringBuilder();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private WebSocket socket;
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) { frames.add(buffer.toString()); buffer.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int status, String reason) {
            closed.complete(status);
            return CompletableFuture.completedFuture(null);
        }
        @Override public void onError(WebSocket webSocket, Throwable error) { closed.completeExceptionally(error); }
        void send(String frame) { socket.sendText(frame, true).join(); }
        String next() throws Exception {
            String value = frames.poll(8, TimeUnit.SECONDS);
            assertNotNull(value, "Không nhận được STOMP frame");
            return value;
        }
        @Override public void close() { if (socket != null) { socket.abort(); } }
    }
}
