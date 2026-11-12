package com.nexus.reputation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.client.OrderVerificationClient.VerifiedOrder;
import com.nexus.reputation.config.ReputationProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Kiểm thử HttpOrderVerificationClient với HTTP server cục bộ: chuyển tiếp JWT và ánh xạ mã lỗi 404/403/500.
 */
class HttpOrderVerificationClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void verifyOrder_parsesOrderPayloadAndForwardsBearerToken() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicInteger statusToReturn = new AtomicInteger(200);
        AtomicReference<String> bodyToReturn = new AtomicReference<>("""
                {"success":true,"data":{"id":"%s","buyerId":"%s","sellerId":"%s","status":"COMPLETED"}}
                """.formatted(orderId, buyerId, sellerId));

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/orders/", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = bodyToReturn.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusToReturn.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        ReputationProperties properties = new ReputationProperties();
        properties.setCommerceBaseUrl("http://localhost:" + server.getAddress().getPort());
        HttpOrderVerificationClient client = new HttpOrderVerificationClient(properties, new ObjectMapper());

        Jwt jwt = Jwt.withTokenValue("test-user-jwt")
                .header("alg", "none")
                .subject(buyerId.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

        VerifiedOrder verified = client.verifyOrder(orderId);
        assertThat(authHeader.get()).isEqualTo("Bearer test-user-jwt");
        assertThat(verified.id()).isEqualTo(orderId);
        assertThat(verified.status()).isEqualTo("COMPLETED");

        statusToReturn.set(404);
        bodyToReturn.set("{\"success\":false}");
        assertThatThrownBy(() -> client.verifyOrder(orderId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.ORDER_NOT_FOUND.name());

        statusToReturn.set(403);
        assertThatThrownBy(() -> client.verifyOrder(orderId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_OWNER.name());

        statusToReturn.set(500);
        assertThatThrownBy(() -> client.verifyOrder(orderId))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.BAD_GATEWAY.name());
    }
}
