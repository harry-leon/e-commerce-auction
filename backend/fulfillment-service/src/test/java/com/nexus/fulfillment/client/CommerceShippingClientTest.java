package com.nexus.fulfillment.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** HTTP mock kiểm tra OAuth riêng, response khớp order và lỗi không đưa upstream PII ra ngoài. */
class CommerceShippingClientTest {
    /** 401 phải xóa cache, lần sau xin token mới; sai order/envelope và redirect không được trả dữ liệu giả. */
    @Test
    void usesServiceBearerRefreshesRejectedTokenAndValidatesSnapshot() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var tokenCalls = new AtomicInteger();
        var code = new AtomicInteger(200);
        UUID order = UUID.randomUUID();
        server.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            tokenCalls.incrementAndGet();
            byte[] body = "{\"access_token\":\"service-test-token\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/internal/commerce/shipping-orders/", exchange -> {
            assertEquals("Bearer service-test-token", exchange.getRequestHeaders().getFirst("Authorization"));
            String id = code.get() == 201 ? UUID.randomUUID().toString() : order.toString();
            byte[] body = ("{\"success\":true,\"data\":{\"orderId\":\"" + id + "\",\"status\":\"PAID\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code.get() == 201 ? 200 : code.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new CommerceShippingClient(base, base + "/token", "nexus-fulfillment-service", "test-only");
            assertEquals(order, client.get(order).orderId());
            assertEquals(order, client.get(order).orderId());
            assertEquals(1, tokenCalls.get());
            code.set(401);
            assertThrows(IllegalStateException.class, () -> client.get(order));
            code.set(200);
            client.get(order);
            assertEquals(2, tokenCalls.get());
            code.set(201);
            assertThrows(IllegalStateException.class, () -> client.get(order));
            code.set(503);
            assertThrows(IllegalStateException.class, () -> client.get(order));
            code.set(302);
            assertThrows(IllegalStateException.class, () -> client.get(order));
        } finally { server.stop(0); }
    }
}
