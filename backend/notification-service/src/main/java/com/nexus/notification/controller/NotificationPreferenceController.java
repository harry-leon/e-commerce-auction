package com.nexus.notification.controller;

import com.nexus.common.exception.ApiException;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cấu hình nhận thông báo theo từng loại sự kiện và kênh cho chính chủ tài khoản.
 *
 * <p>Chức năng: đọc và ghi bảng notification_preferences (V2) để người dùng tắt/mở kênh nhưng không làm mất
 * IN_APP làm nguồn resync. Mọi truy vấn đều ràng buộc user_id lấy từ JWT subject, không nhận userId từ body/query.
 *
 * <p>Quy tắc: IN_APP luôn bật vì là hộp thư bền vững; PUSH được phép tắt để tránh đẩy thiết bị khi người dùng
 * không muốn. Loại sự kiện phải thuộc danh sách Commerce đang phát để tránh tạo bản ghi vô hiệu không ai dùng.
 *
 * <p>Lỗi: token thiếu UUID -> 401, loại/kênh lạ -> 400, không có bản ghi để cập nhật -> 404.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationPreferenceController {
    /** Kênh được phép cấu hình; IN_APP cố định bật nên không nhận ghi đè. */
    private static final java.util.Set<String> CONFIGURABLE_CHANNELS = java.util.Set.of("PUSH", "EMAIL");
    private final JdbcTemplate jdbc;

    public NotificationPreferenceController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Mỗi dòng là một cặp (loại, kênh); thiếu dòng nghĩa là dùng mặc định bật. */
    public record Preference(String type, String channel, boolean enabled) { }
    /** Body cập nhật một cặp; enabled=false là tắt kênh, không xóa khỏi DB để giữ audit cấu hình. */
    public record Update(@NotNull String type, @NotNull String channel, @NotNull Boolean enabled) { }

    /**
     * Trả toàn bộ cấu hình của tài khoản hiện tại, sắp xếp ổn định để client so sánh trước/sau khi lưu.
     * Không trả userId trong response để tránh dùng nhầm sang tài khoản khác.
     */
    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "Đọc cấu hình nhận thông báo của tài khoản hiện tại")
    public ApiResponse<List<Preference>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID user = user(jwt);
        List<Preference> rows = jdbc.query(
                "SELECT notification_type,channel,enabled FROM notification_preferences WHERE user_id=? "
                        + "ORDER BY notification_type,channel",
                (rs, index) -> new Preference(rs.getString("notification_type"), rs.getString("channel"),
                        rs.getBoolean("enabled")), user);
        return ApiResponse.ok(rows);
    }

    /**
     * Ghi đè một cặp (loại, kênh) theo kiểu upsert trên unique (user_id, notification_type, channel).
     * Chỉ tác động dòng của chính JWT subject; giá trị enabled=false giữ nguyên dòng để lần đọc sau thấy cấu hình.
     */
    @PutMapping
    @Transactional
    @Operation(summary = "Bật/tắt một kênh cho một loại thông báo của tài khoản hiện tại")
    public ApiResponse<Preference> update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Update body) {
        UUID user = user(jwt);
        String type = body.type().trim();
        String channel = body.channel().trim().toUpperCase(java.util.Locale.ROOT);
        if (!isKnownType(type) || !CONFIGURABLE_CHANNELS.contains(channel)) {
            throw new ApiException("INVALID_PREFERENCE", HttpStatus.BAD_REQUEST,
                    "Loại thông báo hoặc kênh không được cấu hình", null);
        }
        // ON CONFLICT dựa đúng unique constraint của V2 để hai request đồng thời không sinh dòng trùng.
        jdbc.update("""
                INSERT INTO notification_preferences(id,user_id,notification_type,channel,enabled)
                VALUES (?,?,?,?,?) ON CONFLICT (user_id,notification_type,channel)
                DO UPDATE SET enabled=EXCLUDED.enabled
                """, UUID.randomUUID(), user, type, channel, body.enabled());
        return ApiResponse.ok(new Preference(type, channel, body.enabled()));
    }

    /** Chỉ nhận loại sự kiện Commerce thực sự phát; tránh cấu hình chết cho loại không tồn tại. */
    private boolean isKnownType(String value) {
        for (CommerceNotificationController.Type candidate : CommerceNotificationController.Type.values()) {
            if (candidate.name().equals(value)) { return true; }
        }
        return false;
    }

    /** Subject JWT phải là UUID; token không hợp lệ dừng trước DB để không tạo cấu hình mồ côi. */
    private UUID user(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new ApiException("INVALID_SUBJECT", HttpStatus.UNAUTHORIZED, "JWT không có user UUID hợp lệ", null);
        }
    }
}
