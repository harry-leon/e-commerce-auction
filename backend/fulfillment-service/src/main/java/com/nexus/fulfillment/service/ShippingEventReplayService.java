package com.nexus.fulfillment.service;

import com.nexus.common.exception.ApiException;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin replay riêng event shipment đã hết retry, lưu audit cùng reset; không gọi HTTP hoặc carrier. */
@Service
public class ShippingEventReplayService {
    private static final Set<String> TYPES = Set.of("ShipmentCreated", "ShipmentPickedUp", "ShipmentInTransit",
            "ShipmentDelivered", "ShipmentFailed", "ShipmentReturned");
    private final JdbcTemplate jdbc;
    public ShippingEventReplayService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Khóa actor/key rồi row outbox; retry cùng event/lý do trả audit cũ, nội dung khác hoặc chưa dead letter trả 409. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UUID replay(UUID event, UUID actor, UUID key, String reason) {
        if (event == null || actor == null || key == null || reason == null || reason.isBlank() || reason.length() > 500) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_REPLAY");
        }
        String note = reason.trim();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "shipping-event-replay:" + actor + ":" + key);
        var previous = jdbc.queryForList("SELECT id,event_id,reason FROM shipping_event_replays WHERE actor_id=? AND idempotency_key=?", actor, key);
        if (!previous.isEmpty()) {
            var row = previous.getFirst();
            if (!event.equals(row.get("event_id")) || !note.equals(row.get("reason"))) { throw error(HttpStatus.CONFLICT, "REPLAY_CONFLICT"); }
            return (UUID) row.get("id");
        }
        var rows = jdbc.queryForList("SELECT aggregate_type,event_type,done,attempts FROM fulfillment_event_outbox WHERE id=? FOR UPDATE", event);
        if (rows.isEmpty()) { throw error(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND"); }
        var row = rows.getFirst();
        int attempts = ((Number) row.get("attempts")).intValue();
        // Không cho quyền replay Shipping reset event kho/SKU hoặc một row đang PENDING/đã ACK.
        if (!"SHIPMENT".equals(row.get("aggregate_type")) || !TYPES.contains(row.get("event_type"))
                || !Boolean.FALSE.equals(row.get("done")) || attempts < 8) { throw error(HttpStatus.CONFLICT, "REPLAY_CONFLICT"); }
        UUID audit = UUID.randomUUID();
        jdbc.update("INSERT INTO shipping_event_replays(id,event_id,actor_id,idempotency_key,reason,previous_attempts) VALUES (?,?,?,?,?,?)",
                audit, event, actor, key, note, attempts);
        // Worker lần sau dùng đúng eventId/payload cũ; lỗi audit/reset rollback cùng nhau, không mở lại dispatch carrier.
        jdbc.update("UPDATE fulfillment_event_outbox SET attempts=0,next_attempt_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?", event);
        return audit;
    }
    /** Lỗi không đưa payload, lý do audit hoặc response upstream ra browser/log. */
    private ApiException error(HttpStatus status, String code) {
        return new ApiException(code, status, "Không thể replay event shipment với dữ liệu hoặc trạng thái hiện tại", null);
    }
}
