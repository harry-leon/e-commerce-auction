package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.common.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Wire contract Catalog envelope: lấy giá thật, giữ stock unknown và không fallback mock khi provider lỗi. */
class HttpCatalogClientTest {
    private HttpServer server;
    private HttpCatalogClient client;
    private int status = 200;
    private String body;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpCatalogClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void readsLiveSkuAndDeduplicatesBulkLookup() {
        UUID id = UUID.randomUUID();
        body = "{\"success\":true,\"data\":{\"skuId\":\"" + id
                + "\",\"skuCode\":\"REAL\",\"productName\":\"Product\",\"price\":125000,\"stockQuantity\":null,\"isActive\":true}}";
        var sku = client.getSkuInfo(id);
        assertEquals("REAL", sku.skuCode());
        assertNull(sku.stockQuantity());
        assertEquals(1, client.getSkuDetails(List.of(id, id)).size());
        assertEquals(0, client.getSkuDetails(null).size());
    }

    @Test
    void acceptsCatalogSnapshotWithoutSkuCodeAfterDecoupling() {
        UUID id = UUID.randomUUID();
        body = "{\"success\":true,\"data\":{\"skuId\":\"" + id
                + "\",\"productName\":\"Product Without SkuCode\",\"price\":95000,\"isActive\":true}}";
        var sku = client.getSkuInfo(id);
        assertNull(sku.skuCode());
        assertEquals("Product Without SkuCode", sku.productName());
    }

    @Test
    // Snapshot HTTP phải giữ sellerId thật để checkout kiểm tra chủ shop, kể cả khi Catalog bỏ skuCode.
    void preservesSellerFromFullCatalogSnapshot() {
        UUID id = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        body = "{\"success\":true,\"data\":{\"skuId\":\"" + id
                + "\",\"productId\":\"" + UUID.randomUUID()
                + "\",\"productName\":\"Product\",\"price\":77280,\"originalPrice\":67200,"
                + "\"stockQuantity\":null,\"isActive\":true,\"sellerId\":\"" + seller + "\"}}";
        var sku = client.getSkuInfo(id);
        assertEquals(seller, sku.sellerId());
        assertEquals(seller, client.getSkuDetails(List.of(id)).get(id).sellerId());
    }

    @Test
    void returnsMissingOn404ButFailsClosedOnProviderFailure() {
        body = "{}";
        status = 404;
        assertNull(client.getSkuInfo(UUID.randomUUID()));
        status = 500;
        assertEquals(502, assertThrows(ApiException.class, () -> client.getSkuInfo(UUID.randomUUID())).getHttpStatus().value());
        status = 200;
        body = "{\"success\":false,\"data\":null}";
        assertThrows(ApiException.class, () -> client.getSkuInfo(UUID.randomUUID()));
    }
}
