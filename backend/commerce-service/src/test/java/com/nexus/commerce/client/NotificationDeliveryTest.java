package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

/** HTTP cục bộ bảo vệ bearer service account, refresh 401 và ACK 204; không dùng JWT buyer hoặc Keycloak thật. */
class NotificationDeliveryTest {
    private HttpServer server;
    private NotificationDelivery delivery;
    private final AtomicInteger tokenCalls = new AtomicInteger();
    private int result = 204;
    private String authorization;
    private String payload;
    private String emailPayload;
    private String emailStatus = "SENT";

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            tokenCalls.incrementAndGet();
            byte[] bytes = "{\"access_token\":\"notification-service-token\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/internal/commerce-notifications", exchange -> {
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(result, -1);
            exchange.close();
        });
        // HTTP server giả lập contract Notification; mọi request vẫn phải có service bearer token.
        server.createContext("/api/v1/notifications/transactional-email", exchange -> {
            assertEquals("Bearer notification-service-token", exchange.getRequestHeaders().getFirst("Authorization"));
            emailPayload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = ("{\"success\":true,\"data\":{\"status\":\"" + emailStatus + "\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        delivery = new NotificationDelivery(base, base + "/token", "notification-test", "test-only-secret");
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    /** Refund chỉ dùng inbox/push: chưa có email template, ACK không phụ thuộc endpoint SMTP. */
    @Test
    void refundInboxDoesNotCallUnsupportedEmailTransport() {
        delivery.sendInbox(Map.of("id", "refund-event", "refundId", "refund-id", "type", "REFUND_SUCCEEDED",
                "recipientEmail", "buyer@example.test"));
        assertTrue(payload.contains("refund-id"));
        assertEquals(null, emailPayload);
    }

    /** Gửi lặp dùng cache OAuth; payload giữ UUID snapshot và bearer chỉ là token service account. */
    @Test
    void sendsUsingServiceTokenAndCachesOAuth() {
        assertTrue(delivery.configured());
        delivery.send(Map.of("id", "event-test"));
        delivery.send(Map.of("id", "event-test"));
        assertEquals("Bearer notification-service-token", authorization);
        assertTrue(payload.contains("event-test"));
        assertEquals(1, tokenCalls.get());
    }

    /** 401 ép refresh lần sau; lỗi 503/ACK khác 204 không được worker coi là đã giao thành công. */
    @Test
    void invalidatesRejectedTokenAndRejectsWrongAck() {
        result = 401;
        assertThrows(RestClientResponseException.class, () -> delivery.send(Map.of()));
        result = 204;
        delivery.send(Map.of());
        assertEquals(2, tokenCalls.get());
        result = 503;
        assertThrows(RestClientResponseException.class, () -> delivery.send(Map.of()));
        result = 200;
        assertThrows(IllegalStateException.class, () -> delivery.send(Map.of()));
        assertFalse(new NotificationDelivery("http://localhost", "", "", "").configured());
    }

    /** Email giữ đúng người nhận/số tiền từ snapshot; FAILED/PENDING không được ACK hoàn tất outbox. */
    @Test
    void sendsRealEmailSnapshotAndRetriesUnsentEmail() throws Exception {
        var event = Map.<String, Object>of("id", "event-email", "userId", "buyer-id", "orderId", "order-id",
                "orderNo", "ORD-REAL", "type", "PAYMENT_SUCCESS", "amount", 50000,
                "recipientEmail", "buyer@example.test", "recipientName", "Người mua", "paymentMethod", "VNPAY");
        delivery.send(event);
        var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(emailPayload);
        assertEquals("buyer@example.test", data.get("recipientEmail").asText());
        assertEquals(50000, data.get("totalAmount").asInt());
        assertEquals("ORDER_PAID", data.get("eventType").asText());
        assertEquals("EMAIL-event-email", data.get("eventId").asText());
        emailStatus = "FAILED";
        assertThrows(IllegalStateException.class, () -> delivery.send(event));
        emailStatus = "PENDING";
        assertThrows(IllegalStateException.class, () -> delivery.send(event));
    }

    /** Bảo đảm 3 loại sự kiện đấu giá ánh xạ đúng sang OrderEmailType và giữ nguyên link/deadline/điểm phạt. */
    @Test
    void forwardsAuctionLifecycleEventsWithPaymentLinkAndPenaltyMetadata() throws Exception {
        for (String type : java.util.List.of("AUCTION_WON", "AUCTION_PAYMENT_REMINDER", "AUCTION_PAYMENT_TIMEOUT")) {
            var event = new java.util.LinkedHashMap<String, Object>();
            event.put("id", "evt-" + type);
            event.put("userId", "buyer-id");
            event.put("orderId", "order-id");
            event.put("orderNo", "AUC-TEST");
            event.put("type", type);
            event.put("amount", 1500000);
            event.put("recipientEmail", "winner@nexus.test");
            event.put("paymentLink", "http://localhost:3000/order-detail?id=order-id");
            event.put("paymentDeadline", "2026-11-12T10:00:00Z");
            event.put("penaltyScore", 10);

            delivery.send(event);
            var data = new com.fasterxml.jackson.databind.ObjectMapper().readTree(emailPayload);
            assertEquals(type, data.get("eventType").asText());
            assertEquals("http://localhost:3000/order-detail?id=order-id", data.get("paymentLink").asText());
            assertEquals(10, data.get("penaltyScore").asInt());
        }
    }
}
