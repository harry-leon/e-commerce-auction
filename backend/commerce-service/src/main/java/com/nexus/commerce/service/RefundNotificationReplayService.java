package com.nexus.commerce.service;

import com.nexus.commerce.repository.CheckoutLock;
import com.nexus.common.exception.ApiException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin đưa dead letter về hàng đợi với audit nguyên tử; không gọi HTTP hoặc sửa payload refund. */
@Service
@RequiredArgsConstructor
public class RefundNotificationReplayService {
    private final JdbcTemplate jdbc;
    private final CheckoutLock locks;

    /** Key cùng actor/nội dung trả audit cũ; khóa key rồi event ngăn hai Admin cùng reset một dead letter. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UUID replay(UUID event, UUID actor, UUID key, String reason) {
        if (event == null || actor == null || key == null || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ApiException("INVALID_REPLAY", HttpStatus.BAD_REQUEST, "Replay thiếu định danh hoặc lý do hợp lệ", null);
        }
        String note = reason.trim();
        locks.acquire("refund-notification-replay:" + actor + ":" + key);
        var previous = jdbc.queryForList("SELECT id,event_id,reason FROM refund_notification_replays WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!event.equals(row.get("event_id")) || !note.equals(row.get("reason"))) { throw conflict(); }
            return (UUID) row.get("id");
        }
        var rows = jdbc.queryForList("SELECT status FROM refund_notification_outbox WHERE id=? FOR UPDATE", event);
        if (rows.isEmpty()) { throw new ApiException("EVENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Không tìm thấy thông báo refund", null); }
        if (!"DEAD_LETTER".equals(rows.getFirst().get("status"))) { throw conflict(); }
        UUID audit = UUID.randomUUID();
        // Audit và reset cùng transaction: lỗi ghi audit không được làm mất dấu lần replay; snapshot/id giữ nguyên.
        jdbc.update("INSERT INTO refund_notification_replays(id,event_id,actor_id,idempotency_key,reason) VALUES (?,?,?,?,?)",
                audit, event, actor, key, note);
        jdbc.update("UPDATE refund_notification_outbox SET status='PENDING',attempts=0,next_attempt_at=now(),last_error_type=NULL WHERE id=?", event);
        return audit;
    }

    /** Trạng thái/key không phù hợp phải đối chiếu lại, không reset một sự kiện đang được worker giao. */
    private ApiException conflict() {
        return new ApiException("REPLAY_CONFLICT", HttpStatus.CONFLICT, "Key đã dùng khác nội dung hoặc sự kiện chưa dead letter", null);
    }
}
