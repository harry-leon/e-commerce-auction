package com.nexus.commerce.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.NotificationDelivery;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Giao snapshot refund sau commit qua inbox/push; không gọi provider hoặc thay trạng thái tài chính.
 * Giữ eventId khi mất ACK để Notification khử trùng; tám lỗi liên tiếp đưa vào DEAD_LETTER để vận hành xử lý.
 */
@Component
public class RefundNotificationWorker {
    private final JdbcTemplate jdbc;
    private final NotificationDelivery delivery;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper = new ObjectMapper();

    public RefundNotificationWorker(JdbcTemplate jdbc, NotificationDelivery delivery, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        this.transaction = new TransactionTemplate(manager);
    }

    /** Thiếu OAuth giữ nguyên backlog; mỗi tick xử lý tối đa mười dòng trên scheduler riêng. */
    @Scheduled(fixedDelayString = "${app.refund.notification-delay-ms:10000}", scheduler = "refundNotificationScheduler")
    public void run() {
        if (!delivery.configured()) { return; }
        var ids = jdbc.queryForList("SELECT id FROM refund_notification_outbox WHERE status='PENDING' "
                + "AND next_attempt_at<=now() ORDER BY next_attempt_at,id LIMIT 10", UUID.class);
        ids.forEach(this::deliver);
    }

    /**
     * Khóa riêng dòng outbox trong HTTP có timeout; SKIP LOCKED chia việc giữa instance, không khóa refund/payment.
     * Crash sau ACK sẽ gửi lại cùng snapshot; chỉ ACK 204 mới đánh dấu DELIVERED. Không lưu nội dung lỗi chứa secret.
     */
    public void deliver(UUID id) {
        if (!delivery.configured()) { return; }
        transaction.executeWithoutResult(ignored -> {
            var rows = jdbc.queryForList("SELECT payload::text,attempts FROM refund_notification_outbox "
                    + "WHERE id=? AND status='PENDING' AND next_attempt_at<=now() FOR UPDATE SKIP LOCKED", id);
            if (rows.isEmpty()) { return; }
            var row = rows.getFirst();
            try {
                Map<String, Object> payload = mapper.readValue((String) row.get("payload"), new TypeReference<>() { });
                delivery.sendInbox(payload);
                jdbc.update("UPDATE refund_notification_outbox SET status='DELIVERED',delivered_at=now(),"
                        + "last_error_type=NULL WHERE id=?", id);
            } catch (Exception error) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                // Backoff hữu hạn, không retry vòng lặp trong cùng tick hoặc gửi lại lệnh hoàn tiền.
                jdbc.update("UPDATE refund_notification_outbox SET attempts=?,status=?,last_error_type=?,"
                        + "next_attempt_at=now()+(? * interval '1 second') WHERE id=?",
                        attempts, attempts >= 8 ? "DEAD_LETTER" : "PENDING", error.getClass().getSimpleName(),
                        Math.min(300, 1 << Math.min(attempts, 8)), id);
            }
        });
    }
}
