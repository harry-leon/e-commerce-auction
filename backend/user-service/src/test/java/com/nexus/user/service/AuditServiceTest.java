package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Fake notification qua HTTP kiểm tra wire contract, gửi sau commit và báo provider lỗi thay vì lịch sử rỗng. */
class AuditServiceTest {
    private HttpServer server;
    private AuditService audit;
    private final AtomicInteger writes = new AtomicInteger();
    private volatile int providerStatus = 200;
    private volatile String receivedBody;
    private volatile String receivedAuth;
    private volatile String receivedQuery;
    private volatile String response = "{\"content\":[],\"totalElements\":0}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/user-audit", exchange -> {
            receivedAuth = exchange.getRequestHeaders().getFirst("Authorization");
            receivedQuery = exchange.getRequestURI().getQuery();
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean post = exchange.getRequestMethod().equals("POST");
            if (post) { writes.incrementAndGet(); }
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            int status = providerStatus == 200 && post ? 204 : providerStatus;
            exchange.sendResponseHeaders(status, status == 204 ? -1 : body.length);
            if (status != 204) { exchange.getResponseBody().write(body); }
            exchange.close();
        });
        server.start();
        var keycloak = mock(KeycloakClient.class);
        when(keycloak.serviceToken()).thenReturn("test-service-token");
        audit = new AuditService("http://127.0.0.1:" + server.getAddress().getPort(), keycloak);
    }

    @AfterEach
    void stop() {
        server.stop(0);
        TransactionSynchronizationManager.clear();
    }

    /** Profile chưa commit không được phát audit; cùng event UUID và JSON phải tới notification sau commit. */
    @Test
    void sendsOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        audit.record(UUID.randomUUID(), "USER_UPDATED", "Cập nhật", null, null, "SUCCESS");
        assertEquals(0, writes.get());
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        assertEquals(1, writes.get());
        assertEquals("Bearer test-service-token", receivedAuth);
        org.junit.jupiter.api.Assertions.assertTrue(receivedBody.contains("USER_UPDATED"));
    }

    /** Provider ghi lỗi không làm hỏng thao tác đã commit; đọc lỗi phải trả 503 thay vì danh sách rỗng. */
    @Test
    void handlesProviderFailureAndMissingBody() {
        providerStatus = 503;
        assertDoesNotThrow(() -> audit.record(null, "USER_UPDATED", null, null, null, "SUCCESS"));
        assertEquals(503, assertThrows(ApiException.class,
                () -> audit.history(UUID.randomUUID(), PageRequest.of(0, 10))).getHttpStatus().value());
        providerStatus = 200;
        response = "{}";
        assertThrows(ApiException.class, () -> audit.history(UUID.randomUUID(), PageRequest.of(0, 10)));
    }

    /** Phân trang/sort hợp lệ đi qua HTTP; sort nhiều cột, tên cột lạ và trang quá lớn bị từ chối tại User. */
    @Test
    void pagesHistoryAndRejectsInvalidQueries() {
        UUID event = UUID.randomUUID();
        response = "{\"content\":[{\"id\":\"" + event + "\",\"action\":\"USER_UPDATED\",\"status\":\"SUCCESS\",\"createdAt\":\"2026-10-27T02:03:04\"}],\"totalElements\":1}";
        var page = audit.history(UUID.randomUUID(), PageRequest.of(0, 10));
        assertEquals(event, page.getContent().getFirst().getId());
        assertEquals(1, page.getTotalElements());
        org.junit.jupiter.api.Assertions.assertTrue(receivedQuery.contains("sort=createdAt,desc"));
        assertThrows(ApiException.class, () -> audit.history(UUID.randomUUID(), Pageable.unpaged()));
        assertThrows(ApiException.class, () -> audit.history(UUID.randomUUID(), PageRequest.of(0, 101)));
        assertThrows(ApiException.class, () -> audit.history(UUID.randomUUID(), PageRequest.of(0, 10, Sort.by("payload"))));
        assertThrows(ApiException.class, () -> audit.history(UUID.randomUUID(), PageRequest.of(0, 10, Sort.by("id", "status"))));
    }
}
