package com.nexus.notification.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Hộp thư IN_APP theo JWT subject; mọi truy vấn/cập nhật đều ràng buộc user_id để ngăn IDOR. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final JdbcTemplate jdbc;

    public NotificationController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Ngữ cảnh refund chỉ hiển thị cho chủ inbox; không lộ evidence, provider reference hoặc lỗi giao nhận nội bộ. */
    public record Item(UUID id, UUID orderId, String type, String title, String content, boolean read, Instant createdAt, UUID refundId) {
        public Item(UUID id, UUID orderId, String type, String title, String content, boolean read, Instant createdAt) {
            this(id, orderId, type, title, content, read, createdAt, null);
        }
    }
    /** Tổng số chưa đọc tính toàn hộp thư, không chỉ riêng trang đang xem. */
    public record Inbox(List<Item> content, long totalElements, long unreadCount) { }

    /** Phân trang ổn định theo thời gian rồi UUID; giới hạn 100 bản ghi và không chấp nhận userId từ query. */
    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Operation(summary = "Đọc thông báo IN_APP của tài khoản hiện tại")
    public ApiResponse<Inbox> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new ApiException("INVALID_PAGE", HttpStatus.BAD_REQUEST, "Phân trang không hợp lệ", null);
        }
        UUID user = user(jwt);
        String where = " FROM notifications WHERE user_id=? AND channel='IN_APP' AND status IN ('SENT','READ')";
        List<Item> rows = jdbc.query("SELECT id,order_id,event_type,title,content,status,created_at,refund_id" + where
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (rs, row) -> new Item(
                        rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class), rs.getString("event_type"),
                        rs.getString("title"), rs.getString("content"), "READ".equals(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC),
                        rs.getObject("refund_id", UUID.class)), user, size, (long) page * size);
        long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, user);
        long unread = jdbc.queryForObject("SELECT count(*)" + where + " AND status='SENT'", Long.class, user);
        return ApiResponse.ok(new Inbox(rows, total, unread));
    }

    /** Đọc lặp vẫn thành công; UUID không thuộc người dùng trả 404, không tiết lộ bản ghi của người khác. */
    @PostMapping("/{id}/read")
    @Operation(summary = "Đánh dấu đã đọc một thông báo của tài khoản hiện tại")
    public ApiResponse<Void> read(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        int updated = jdbc.update("UPDATE notifications SET status='READ' WHERE id=? AND user_id=? "
                + "AND channel='IN_APP' AND status IN ('SENT','READ')", id, user(jwt));
        if (updated == 0) { throw new ApiException("NOTIFICATION_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy thông báo", null); }
        return ApiResponse.ok((Void) null);
    }

    /** Chỉ đánh dấu hộp thư đã giao của JWT hiện tại, không tác động người nhận khác hoặc thông báo pending. */
    @PostMapping("/read-all")
    @Operation(summary = "Đánh dấu đã đọc toàn bộ hộp thư hiện tại")
    public ApiResponse<Void> readAll(@AuthenticationPrincipal Jwt jwt) {
        jdbc.update("UPDATE notifications SET status='READ' WHERE user_id=? AND channel='IN_APP' AND status='SENT'", user(jwt));
        return ApiResponse.ok((Void) null);
    }

    private UUID user(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT không có user UUID hợp lệ", null);
        }
    }
}
