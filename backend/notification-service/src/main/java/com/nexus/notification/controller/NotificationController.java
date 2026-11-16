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

    /**
     * DTO thông báo hiển thị cho người dùng: hỗ trợ cả đơn hàng, hoàn tiền, đấu giá và liên kết thanh toán.
     * Ngữ cảnh refund/auction chỉ hiển thị cho chủ inbox; không lộ evidence, provider reference hoặc lỗi nội bộ.
     */
    public record Item(
            UUID id,
            UUID orderId,
            String type,
            String title,
            String content,
            boolean read,
            Instant createdAt,
            UUID refundId,
            UUID auctionId,
            String paymentLink,
            Instant paymentDeadline
    ) {
        /** Constructor tương thích ngược cho client/test 7 tham số. */
        public Item(UUID id, UUID orderId, String type, String title, String content, boolean read, Instant createdAt) {
            this(id, orderId, type, title, content, read, createdAt, null, null, null, null);
        }

        /** Constructor tương thích ngược cho ngữ cảnh refund 8 tham số. */
        public Item(UUID id, UUID orderId, String type, String title, String content, boolean read, Instant createdAt, UUID refundId) {
            this(id, orderId, type, title, content, read, createdAt, refundId, null, null, null);
        }

        /** Constructor tương thích ngược cho ngữ cảnh đấu giá 10 tham số. */
        public Item(UUID id, UUID orderId, String type, String title, String content, boolean read, Instant createdAt, UUID auctionId, String paymentLink, Instant paymentDeadline) {
            this(id, orderId, type, title, content, read, createdAt, null, auctionId, paymentLink, paymentDeadline);
        }
    }
    /** Tổng số chưa đọc tính toàn hộp thư, không chỉ riêng trang đang xem. */
    public record Inbox(List<Item> content, long totalElements, long unreadCount) { }

    /**
     * Phân trang ổn định theo thời gian rồi UUID; giới hạn 100 bản ghi và không chấp nhận userId từ query.
     * Bộ lọc tùy chọn theo loại sự kiện, chỉ chưa đọc và khoảng thời gian giúp tra cứu lịch sử mà vẫn khóa theo JWT subject.
     */
    @GetMapping
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    @Operation(summary = "Đọc thông báo IN_APP của tài khoản hiện tại, lọc theo loại/trạng thái/thời gian")
    public ApiResponse<Inbox> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) {
            throw new ApiException("INVALID_PAGE", HttpStatus.BAD_REQUEST, "Phân trang không hợp lệ", null);
        }
        UUID user = user(jwt);
        String eventType = type == null || type.isBlank() ? null : type.trim();
        if (eventType != null && !isKnownType(eventType)) {
            throw new ApiException("INVALID_FILTER", HttpStatus.BAD_REQUEST, "Loại thông báo không hợp lệ", null);
        }
        Instant fromInstant = parseBound(from);
        Instant toInstant = parseBound(to);
        if (fromInstant != null && toInstant != null && fromInstant.isAfter(toInstant)) {
            throw new ApiException("INVALID_FILTER", HttpStatus.BAD_REQUEST, "Khoảng thời gian không hợp lệ", null);
        }
        // Tham số bind theo đúng thứ tự xuất hiện trong WHERE; userId luôn đứng đầu để không lọc chéo người dùng.
        List<Object> args = new java.util.ArrayList<>();
        args.add(user);
        StringBuilder where = new StringBuilder(
                " FROM notifications WHERE user_id=? AND channel='IN_APP' AND status IN ('SENT','READ')");
        if (eventType != null) {
            where.append(" AND event_type=?");
            args.add(eventType);
        }
        if (fromInstant != null) {
            where.append(" AND created_at>=?");
            args.add(java.sql.Timestamp.from(fromInstant));
        }
        if (toInstant != null) {
            where.append(" AND created_at<=?");
            args.add(java.sql.Timestamp.from(toInstant));
        }
        // unreadOnly chỉ thu hẹp danh sách; total/unread vẫn tính trên bộ lọc loại/thời gian gốc
        // để badge chưa đọc không bị "biến mất" khi client đang xem tab chưa đọc.
        String listWhere = unreadOnly ? where + " AND status='SENT'" : where.toString();
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<Item> rows = jdbc.query("SELECT id,order_id,event_type,title,content,status,created_at,refund_id,auction_id,payment_link,payment_deadline" + listWhere
                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", (rs, row) -> new Item(
                        rs.getObject("id", UUID.class),
                        rs.getObject("order_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("title"),
                        rs.getString("content"),
                        "READ".equals(rs.getString("status")),
                        rs.getTimestamp("created_at").toLocalDateTime().toInstant(java.time.ZoneOffset.UTC),
                        rs.getObject("refund_id", UUID.class),
                        rs.getObject("auction_id", UUID.class),
                        rs.getString("payment_link"),
                        rs.getTimestamp("payment_deadline") != null ? rs.getTimestamp("payment_deadline").toInstant() : null), pageArgs.toArray());
        long total = jdbc.queryForObject("SELECT count(*)" + listWhere, Long.class, args.toArray());
        long unread = jdbc.queryForObject("SELECT count(*)" + where + " AND status='SENT'", Long.class, args.toArray());
        return ApiResponse.ok(new Inbox(rows, total, unread));
    }

    /** Chỉ nhận loại sự kiện do hệ thống sinh ra; giá trị tự do bị từ chối trước khi chạm DB. */
    private boolean isKnownType(String value) {
        for (CommerceNotificationController.Type candidate : CommerceNotificationController.Type.values()) {
            if (candidate.name().equals(value)) { return true; }
        }
        return false;
    }

    /** Mốc thời gian ISO-8601 tùy chọn; rỗng trả null, sai định dạng trả 400 thay vì lỗi 500 khi bind cột. */
    private Instant parseBound(String value) {
        if (value == null || value.isBlank()) { return null; }
        try {
            return Instant.parse(value.trim());
        } catch (java.time.format.DateTimeParseException error) {
            throw new ApiException("INVALID_FILTER", HttpStatus.BAD_REQUEST,
                    "Mốc thời gian phải theo ISO-8601 (ví dụ 2026-11-01T00:00:00Z)", null);
        }
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
