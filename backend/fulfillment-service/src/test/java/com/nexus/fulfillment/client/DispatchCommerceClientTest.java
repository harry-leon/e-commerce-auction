package com.nexus.fulfillment.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** HTTP/OAuth fixture loopback kiểm tra wire contract, binding và không retry/redirect; không gọi Commerce/IAM thật. */
class DispatchCommerceClientTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger oauth = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<byte[]> body = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> bearer = new AtomicReference<>();
    private HttpServer server;
    private DispatchCommerceClient client;

    /** Mỗi test có cổng/response riêng; private fixture secret/token chỉ dùng trong process, không ghi log. */
    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oauth", exchange -> {
            oauth.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"access_token\":\"fixture-token\",\"expires_in\":300}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (exchange) { exchange.getResponseBody().write(bytes); }
        });
        server.createContext("/api/v1/internal/commerce", exchange -> {
            calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            lastKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("Location", "/must-not-follow");
            exchange.sendResponseHeaders(status.get(), body.get().length);
            try (exchange) { exchange.getResponseBody().write(body.get()); }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        client = new DispatchCommerceClient(url, url + "/oauth", "fixture-client", "fixture-only");
    }

    @AfterEach
    void stop() { if (client != null) { client.shutdown(); } if (server != null) { server.stop(0); } }

    /** Handoff/claim/preflight giữ đúng UUID, key và bearer; cache OAuth chung trong client không xin token mỗi call. */
    @Test
    void bindsHandoffClaimAndPreflightWithStableKey() throws Exception {
        UUID order = UUID.randomUUID(); UUID handoff = UUID.randomUUID(); UUID parcel = UUID.randomUUID(); UUID key = UUID.randomUUID();
        response(new DispatchCommerceClient.Handoff(handoff, order, "WAITING_PARCEL"));
        assertEquals(handoff, client.handoff(order).id());
        var claim = new DispatchCommerceClient.Claim(UUID.randomUUID(), order, handoff, parcel, Instant.now());
        status.set(201); response(claim);
        assertEquals(claim, client.claim(order, handoff, parcel, key)); assertEquals(key.toString(), lastKey.get());
        status.set(200); response(new DispatchCommerceClient.Authorization(claim,
                new CommerceShippingClient.Snapshot(order, "FIXTURE", "PAID", "Fixture", "0900000000", "Fixture address", "VNPAY", true, BigDecimal.TEN, null)));
        assertEquals(order, client.preflight(claim).snapshot().orderId());
        assertEquals("Bearer fixture-token", bearer.get()); assertEquals(1, oauth.get()); assertEquals(3, calls.get());
    }

    /** Redirect/503 không gọi lại ngầm, response lỗi không được nối secret/body vào exception. */
    @Test
    void doesNotRedirectRetryOrExposeResponse() {
        body.set("must-not-leak-provider-body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (int code : new int[]{302, 503}) {
            status.set(code);
            var error = assertThrows(DispatchCommerceClient.Fault.class, () -> client.handoff(UUID.randomUUID()));
            assertEquals(code, error.status()); assertFalse(error.toString().contains("must-not-leak"));
        }
        assertEquals(2, calls.get());
    }

    /** 401 xóa token để request sau xin mới, không gửi lại claim trong cùng invocation. */
    @Test
    void refreshesOnlyOnNextInvocationAfter401() throws Exception {
        UUID order = UUID.randomUUID(); status.set(401); body.set(new byte[0]);
        assertEquals(401, assertThrows(DispatchCommerceClient.Fault.class, () -> client.handoff(order)).status());
        assertEquals(1, calls.get()); assertEquals(1, oauth.get());
        status.set(200); response(new DispatchCommerceClient.Handoff(UUID.randomUUID(), order, "WAITING_PARCEL"));
        client.handoff(order); assertEquals(2, oauth.get());
    }

    /** Envelope sai hoặc UUID khác phải fail closed, không dùng snapshot thuộc đơn/kiện khác. */
    @Test
    void rejectsMalformedAndMismatchedResponses() throws Exception {
        body.set("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(422, assertThrows(DispatchCommerceClient.Fault.class, () -> client.handoff(UUID.randomUUID())).status());
        response(new DispatchCommerceClient.Handoff(UUID.randomUUID(), UUID.randomUUID(), "WAITING_PARCEL"));
        assertEquals(422, assertThrows(DispatchCommerceClient.Fault.class, () -> client.handoff(UUID.randomUUID())).status());
    }

    private void response(Object data) throws Exception { body.set(mapper.writeValueAsBytes(Map.of("success", true, "data", data))); }
}
