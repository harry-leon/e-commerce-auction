package com.nexus.user.service;

import com.nexus.common.exception.ApiException;
import com.nexus.user.dto.ActivityHistoryResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

/**
 * Chuyển audit sang notification_svc.event_logs theo ERD; user-service không còn bảng audit riêng.
 * Ghi sau commit profile, timeout hữu hạn và best-effort: lỗi ghi audit không rollback profile đã commit.
 * Chưa có outbox/retry bền vững; nếu notification ngừng hoạt động, ghi cảnh báo và có thể mất sự kiện.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final RestClient client;
    private final KeycloakClient keycloak;

    public AuditService(@Value("${app.notification.base-url:http://localhost:8086}") String baseUrl,
            KeycloakClient keycloak) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.keycloak = keycloak;
    }

    /** Contract nội bộ có UUID ổn định, timestamp UTC và người dùng đích; không chứa credential. */
    public record AuditEvent(UUID id, UUID userId, String action, String description,
            String ipAddress, String userAgent, String status, LocalDateTime createdAt) { }

    /** Response nội bộ tách khỏi PageImpl để Jackson không cần khởi tạo Pageable qua HTTP. */
    public record HistoryPage(List<ActivityHistoryResponse> content, long totalElements) { }

    /** Nhận nội dung audit từ nghiệp vụ; trì hoãn HTTP tới sau commit để không lưu sự kiện của transaction rollback. */
    public void record(UUID userId, String action, String description,
            String ipAddress, String userAgent, String status) {
        var event = new AuditEvent(UUID.randomUUID(), userId, action, description, ipAddress,
                userAgent, status, LocalDateTime.now(ZoneOffset.UTC));
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { send(event); }
            });
        } else {
            send(event);
        }
    }

    /** Chỉ gửi bằng service token; không log token, payload hay response chứa dữ liệu cá nhân khi provider lỗi. */
    private void send(AuditEvent event) {
        try {
            client.post().uri("/internal/user-audit")
                    .headers(headers -> headers.setBearerAuth(keycloak.serviceToken()))
                    .body(event).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Không gửi được audit event {} sang notification-service ({})", event.id(), e.getClass().getSimpleName());
        }
    }

    /** Đọc lịch sử từ notification; lỗi mạng trả 503, không trả danh sách rỗng gây hiểu nhầm là không có hoạt động. */
    public Page<ActivityHistoryResponse> history(UUID userId, Pageable pageable) {
        if (pageable.isUnpaged() || pageable.getPageSize() > 100) {
            throw new ApiException("INVALID_PAGE", HttpStatus.BAD_REQUEST, "Kích thước trang tối đa 100", null);
        }
        var order = pageable.getSort().stream().findFirst().orElse(
                org.springframework.data.domain.Sort.Order.desc("createdAt"));
        if (pageable.getSort().stream().count() > 1 || !List.of("createdAt", "id", "action", "status").contains(order.getProperty())) {
            throw new ApiException("INVALID_SORT", HttpStatus.BAD_REQUEST, "Sort lịch sử không hợp lệ", null);
        }
        try {
            HistoryPage result = client.get().uri(builder -> builder.path("/internal/user-audit/{userId}")
                    .queryParam("page", pageable.getPageNumber()).queryParam("size", pageable.getPageSize())
                    .queryParam("sort", order.getProperty() + "," + order.getDirection().name().toLowerCase(java.util.Locale.ROOT))
                    .build(userId)).headers(headers -> headers.setBearerAuth(keycloak.serviceToken()))
                    .retrieve().body(HistoryPage.class);
            if (result == null || result.content() == null) { throw new IllegalStateException("Thiếu lịch sử audit"); }
            return new PageImpl<>(result.content(), pageable, result.totalElements());
        } catch (RuntimeException e) {
            throw new ApiException("AUDIT_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "Không thể đọc lịch sử hoạt động từ notification-service", null);
        }
    }
}
