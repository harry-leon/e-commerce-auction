package com.nexus.auction.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Kiểm thử HttpReputationEligibilityClient với HTTP server cục bộ:
 * cho qua khi eligible=true, ném REPUTATION_ACTION_FORBIDDEN khi eligible=false và fail-closed khi lỗi 5xx.
 */
class HttpReputationEligibilityClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void assertEligible_enforcesEligibilityAndFailClosedBehavior() throws Exception {
        UUID userId = UUID.randomUUID();
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> body = new AtomicReference<>("""
                {"success":true,"data":{"userId":"%s","action":"BID","eligible":true,"score":80,"minRequiredScore":40,"trustLevel":"STANDARD","reason":"Đủ điều kiện"}}
                """.formatted(userId));
        AtomicReference<String> query = new AtomicReference<>();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/internal/reputations/", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        RestClient restClient = RestClient.create("http://localhost:" + server.getAddress().getPort());
        HttpReputationEligibilityClient client = new HttpReputationEligibilityClient(restClient);

        assertThatCode(() -> client.assertEligible(userId, "BID")).doesNotThrowAnyException();
        assertThat(query.get()).isEqualTo("action=BID");

        body.set("""
                {"success":true,"data":{"userId":"%s","action":"CREATE_AUCTION","eligible":false,"score":35,"minRequiredScore":50,"trustLevel":"LOW","reason":"Điểm dưới 50"}}
                """.formatted(userId));
        assertThatThrownBy(() -> client.assertEligible(userId, "CREATE_AUCTION"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REPUTATION_ACTION_FORBIDDEN.name());

        status.set(503);
        body.set("{\"success\":false}");
        assertThatThrownBy(() -> client.assertEligible(userId, "BID"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.name());
    }
}
