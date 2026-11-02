package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Service account chỉ dùng fixture HTTP cục bộ: xác minh client_credentials, cache và refresh khi kho từ chối token cũ. */
class InventoryServiceTokenTest {
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private String body = "{\"access_token\":\"test-token\",\"expires_in\":300}";
    private String request;
    private InventoryServiceToken tokens;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        tokens = new InventoryServiceToken("http://127.0.0.1:" + server.getAddress().getPort() + "/token", "test-client", "test-only-secret");
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void cachesServiceTokenAndRefreshesAfterInvalidation() {
        assertTrue(tokens.configured());
        assertEquals("test-token", tokens.get());
        assertEquals("test-token", tokens.get());
        assertEquals(1, calls.get());
        assertTrue(request.contains("grant_type=client_credentials"));
        assertTrue(request.contains("client_id=test-client"));
        tokens.invalidate();
        tokens.get();
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsMissingCredentialsAndMalformedTokenInsteadOfSendingBuyerToken() {
        var disabled = new InventoryServiceToken("", "", "");
        assertFalse(disabled.configured());
        assertThrows(IllegalStateException.class, disabled::get);
        body = "{\"access_token\":\"\",\"expires_in\":300}";
        assertThrows(IllegalStateException.class, tokens::get);
    }
}
