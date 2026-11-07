package com.nexus.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** IAM fixture kiểm tra grant, cache/invalidate và credentials rỗng; không gọi Keycloak thật. */
class ClientCredentialsTokenTest {
    private HttpServer server;
    @AfterEach void cleanup() { if (server != null) { server.stop(0); } }

    @Test
    void cachesTokenAndRefreshesAfterUnauthorized() throws Exception {
        var calls = new AtomicInteger();
        var request = new java.util.concurrent.atomic.AtomicReference<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            calls.incrementAndGet();
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"access_token\":\"service-token\",\"expires_in\":300,\"token_type\":\"Bearer\",\"scope\":\"roles\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var token = new ClientCredentialsToken("http://127.0.0.1:" + server.getAddress().getPort() + "/token", "auction", "test-secret");
        assertEquals("service-token", token.get());
        assertEquals("service-token", token.get());
        assertEquals(1, calls.get());
        assertTrue(request.get().contains("grant_type=client_credentials"));
        assertTrue(request.get().contains("client_id=auction"));
        token.invalidate();
        token.get();
        assertEquals(2, calls.get());
        assertThrows(IllegalStateException.class, () -> new ClientCredentialsToken("", "", "").get());
    }
}
