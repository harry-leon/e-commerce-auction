package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/**
 * Kiểm thử HTTP client gọi User, Reputation và Auction khi có sự cố mạng, lỗi 401 refresh token và timeout.
 */
class HttpAuctionTimeoutAndUserClientTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private volatile int reputationStatus = 200;
    private volatile int auctionStatus = 200;
    private volatile int userStatus = 200;
    private volatile String lastPenaltyBody;
    private volatile String lastAuctionBody;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = tokenCalls.incrementAndGet();
            byte[] bytes = ("{\"access_token\":\"commerce-token-" + n + "\",\"expires_in\":300}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/internal/reputations/penalties", exchange -> {
            lastPenaltyBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = reputationStatus == 200
                    ? "{\"success\":true,\"data\":{\"penaltyId\":\"11111111-1111-1111-1111-111111111111\",\"scoreDelta\":-10}}"
                            .getBytes(StandardCharsets.UTF_8)
                    : "{\"success\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(reputationStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/internal/auctions/", exchange -> {
            lastAuctionBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = auctionStatus == 200
                    ? "{\"success\":true,\"data\":{\"result\":\"DEFAULTED\"}}".getBytes(StandardCharsets.UTF_8)
                    : "{\"success\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(auctionStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/internal/users/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = userStatus == 200
                    ? "{\"success\":true,\"data\":{\"id\":\"11111111-1111-1111-1111-111111111111\",\"email\":\"winner@nexus.test\",\"fullName\":\"Winner Test\"}}"
                            .getBytes(StandardCharsets.UTF_8)
                    : "{\"success\":false}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(userStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /** Lỗi 401 làm mới token, lỗi 503 ném exception cho outbox giữ backlog và retry thành công khi hồi phục. */
    @Test
    void handlesTokenRefreshAndServerRecoveryForPenaltyAndAuctionSync() {
        InventoryServiceToken token = new InventoryServiceToken(
                baseUrl + "/token", "nexus-commerce-service", "test-secret");
        HttpAuctionTimeoutSyncClient client = new HttpAuctionTimeoutSyncClient(baseUrl, baseUrl, token);

        assertTrue(client.configured());
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        reputationStatus = 401;
        assertThrows(RestClientResponseException.class, () ->
                client.applyTimeoutPenalty(userId, auctionId, orderId, 10, "Quá hạn thanh toán 24h"));

        reputationStatus = 200;
        client.applyTimeoutPenalty(userId, auctionId, orderId, 10, "Quá hạn thanh toán 24h");
        assertEquals(2, tokenCalls.get());
        assertTrue(lastPenaltyBody.contains("\"penaltyPoints\":10"));
        assertTrue(lastPenaltyBody.contains(auctionId.toString()));

        auctionStatus = 503;
        assertThrows(RestClientResponseException.class, () -> client.markAuctionDefaulted(auctionId, orderId));

        auctionStatus = 200;
        client.markAuctionDefaulted(auctionId, orderId);
        assertTrue(lastAuctionBody.contains(orderId.toString()));
    }

    /** Lỗi tra cứu User Service không làm sập luồng tạo đơn mà fallback về Optional.empty an toàn. */
    @Test
    void userContactClientReturnsContactAndDegradesGracefullyOnError() {
        InventoryServiceToken token = new InventoryServiceToken(
                baseUrl + "/token", "nexus-commerce-service", "test-secret");
        HttpUserContactClient userClient = new HttpUserContactClient(baseUrl, token);

        var ok = userClient.findContact(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertTrue(ok.isPresent());
        assertEquals("winner@nexus.test", ok.get().email());

        userStatus = 503;
        var degraded = userClient.findContact(UUID.randomUUID());
        assertTrue(degraded.isEmpty());
    }
}
