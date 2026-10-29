package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Wire test bằng HTTP server thật: đúng refId/timeout/token, lỗi thiếu hàng và response hỏng không được coi là thành công. */
class HttpInventoryClientTest {
    private HttpServer server;
    private HttpInventoryClient client;
    private int status = 200;
    private String body = "{\"success\":true,\"data\":{\"availableQty\":8,\"totalQty\":10,\"reservedQty\":2,\"warehouseCode\":\"DEFAULT\"}}";
    private String requestBody;
    private String authorization;
    private String path;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            path = exchange.getRequestURI().getPath();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpInventoryClient("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void readsStockAndSendsReserveAndReleaseWithSameReference() {
        UUID id = UUID.randomUUID();
        assertEquals(8, client.available(id));
        UUID sku = UUID.randomUUID();
        body = "{\"success\":true,\"data\":{\"refType\":\"ORDER\",\"refId\":\"" + id
                + "\",\"status\":\"PENDING\",\"items\":[{\"reservationId\":\"" + UUID.randomUUID()
                + "\",\"skuId\":\"" + sku + "\",\"quantity\":2,\"status\":\"PENDING\"}]}}";
        client.reserve(id, List.of(new InventoryClient.Item(sku, null, 2)), "verified-jwt");
        assertEquals("/api/v1/inventory/reserve", path);
        assertEquals("Bearer verified-jwt", authorization);
        assertTrue(requestBody.contains(id.toString()));
        assertTrue(requestBody.contains("\"timeoutMinutes\":15"));
        body = "{\"success\":true,\"data\":null}";
        client.release(id, "verified-jwt");
        assertEquals("/api/v1/inventory/release", path);
        assertTrue(requestBody.contains(id.toString()));
    }

    @Test
    void preservesBusinessErrorAndFailsClosedOnMalformedResponses() {
        UUID id = UUID.randomUUID();
        status = 409;
        body = "{\"success\":false,\"error\":{\"code\":\"INVENTORY_INSUFFICIENT\",\"message\":\"Thiếu kho\"}}";
        var error = assertThrows(ApiException.class, () -> client.reserve(id, List.of(), "jwt"));
        assertEquals("INVENTORY_INSUFFICIENT", error.getCode());
        assertEquals(409, error.getHttpStatus().value());
        status = 404;
        assertEquals(0, client.available(id));
        body = "proxy error";
        assertEquals(502, assertThrows(ApiException.class, () -> client.release(id, "jwt")).getHttpStatus().value());
        status = 500;
        assertThrows(ApiException.class, () -> client.available(id));
        status = 200;
        body = "{\"success\":true,\"data\":null}";
        assertThrows(ApiException.class, () -> client.reserve(id, List.of(), "jwt"));
        assertThrows(ApiException.class, () -> client.available(id));
        body = "invalid-json";
        assertThrows(ApiException.class, () -> client.available(id));
        assertThrows(ApiException.class, () -> client.release(id, "jwt"));
    }

    /** Kho trả nhầm ref, thiếu item hoặc sai quantity phải rollback thay vì tạo đơn chưa được giữ đủ hàng. */
    @Test
    void rejectsMismatchedReservationReceipts() {
        UUID id = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        var items = List.of(new InventoryClient.Item(sku, null, 2));
        body = "{\"success\":true,\"data\":{}}";
        assertThrows(ApiException.class, () -> client.reserve(id, items, "jwt"));
        body = "{\"success\":true,\"data\":{\"refType\":\"ORDER\",\"refId\":\"" + id
                + "\",\"status\":\"PENDING\",\"items\":[]}}";
        assertThrows(ApiException.class, () -> client.reserve(id, items, "jwt"));
        body = "{\"success\":true,\"data\":{\"refType\":\"ORDER\",\"refId\":\"" + id
                + "\",\"status\":\"PENDING\",\"items\":[{\"reservationId\":\"" + UUID.randomUUID()
                + "\",\"skuId\":\"" + sku + "\",\"quantity\":1,\"status\":\"PENDING\"}]}}";
        assertThrows(ApiException.class, () -> client.reserve(id, items, "jwt"));
    }
}
