package com.nexus.notification.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.nexus.common.exception.ApiException;

/**
 * Lưu/đọc audit hồ sơ tại notification_svc.event_logs đúng ERD, không tạo audit_logs ở user-service.
 * Chỉ service account có PROFILE_WRITER được gọi; user-service kiểm tra quyền xem profile trước khi chuyển tiếp.
 * Lỗi validation dùng ApiException để handler chung giữ đúng mã HTTP, không biến thành 500.
 * Payload JSON chứa người dùng đích và nội dung audit; operator_id lấy từ JWT đã xác thực, không tin client.
 */
@RestController
@RequestMapping("/internal/user-audit")
@PreAuthorize("hasRole('PROFILE_WRITER')")
public class UserAuditController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UserAuditController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** UUID sự kiện giữ ổn định khi gửi lại; giới hạn payload để tương thích contract lịch sử hoạt động. */
    public record AuditEvent(@NotNull UUID id, UUID userId,
            @NotBlank @Size(max = 100) String action, String description,
            @Size(max = 100) String ipAddress, @Size(max = 255) String userAgent,
            @NotBlank @Size(max = 50) String status, @NotNull LocalDateTime createdAt) { }

    /** DTO phân trang nội bộ; không phụ thuộc định dạng serialize PageImpl giữa các service. */
    public record HistoryPage(List<Activity> content, long totalElements) { }

    /** Chỉ trả dữ liệu hiển thị lịch sử; IP, user agent và thông tin JWT không đi ra API người dùng. */
    public record Activity(UUID id, String action, String description, String status, LocalDateTime createdAt) { }

    /** Ghi vào bảng ERD bằng tham số SQL; PK sự kiện chặn ghi trùng nếu client gửi lại cùng UUID. */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@Valid @RequestBody AuditEvent event, @AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", event.userId());
        payload.put("action", event.action());
        payload.put("description", event.description());
        payload.put("status", event.status());
        payload.put("ipAddress", event.ipAddress());
        payload.put("userAgent", event.userAgent());
        try {
            jdbc.update("""
                    INSERT INTO event_logs (id, event_id, source_service, payload, operator_id, created_at)
                    VALUES (?, ?, 'user-service', CAST(? AS jsonb), ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, event.id(), event.id().toString(), mapper.writeValueAsString(payload),
                    jwt.getSubject(), event.createdAt());
        } catch (JsonProcessingException e) {
            throw new ApiException("INVALID_AUDIT_PAYLOAD", HttpStatus.BAD_REQUEST, "Payload audit không hợp lệ", null);
        }
    }

    /** Lọc theo user đích trong payload, phân trang giới hạn 100; sort dùng whitelist để tránh SQL injection. */
    @GetMapping("/{userId}")
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HistoryPage history(@PathVariable UUID userId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException("INVALID_PAGE", HttpStatus.BAD_REQUEST, "Phân trang không hợp lệ", null);
        }
        String[] parts = sort.split(",", -1);
        String column = Map.of("createdAt", "created_at", "id", "id", "action", "payload->>'action'",
                "status", "payload->>'status'").get(parts[0]);
        if (column == null || parts.length != 2 || !(parts[1].equals("asc") || parts[1].equals("desc"))) {
            throw new ApiException("INVALID_SORT", HttpStatus.BAD_REQUEST, "Sort không hợp lệ", null);
        }
        String where = " FROM event_logs WHERE source_service = 'user-service' AND payload->>'userId' = ?";
        Long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, userId.toString());
        // Chỉ column/direction đã whitelist được ghép SQL; userId/limit/offset luôn bind qua JDBC.
        List<Activity> content = jdbc.query("SELECT id, payload, created_at" + where + " ORDER BY "
                + column + " " + parts[1] + ", id DESC LIMIT ? OFFSET ?", (rs, row) -> {
                    try {
                        var payload = mapper.readTree(rs.getString("payload"));
                        return new Activity(rs.getObject("id", UUID.class), payload.path("action").asText(),
                                payload.path("description").isNull() ? null : payload.path("description").asText(),
                                payload.path("status").asText(), rs.getTimestamp("created_at").toLocalDateTime());
                    } catch (JsonProcessingException e) {
                        throw new java.sql.SQLException("Payload audit không hợp lệ", e);
                    }
                }, userId.toString(), size, (long) page * size);
        return new HistoryPage(content, total == null ? 0 : total);
    }
}
