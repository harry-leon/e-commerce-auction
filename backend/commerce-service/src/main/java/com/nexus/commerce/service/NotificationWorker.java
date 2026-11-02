package com.nexus.commerce.service;

import com.nexus.commerce.client.NotificationDelivery;
import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox IN_APP độc lập với giao dịch checkout/IPN; khóa SKIP LOCKED cho phép nhiều instance chia backlog. */
@Component
@Slf4j
public class NotificationWorker {
    private final JdbcTemplate jdbc;
    private final NotificationDelivery delivery;
    private final TransactionTemplate transaction;
    private final java.util.concurrent.atomic.AtomicBoolean configWarning = new java.util.concurrent.atomic.AtomicBoolean();

    public NotificationWorker(JdbcTemplate jdbc, NotificationDelivery delivery, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.delivery = delivery;
        transaction = new TransactionTemplate(manager);
    }

    /** Tối đa 10 sự kiện/tick, mỗi sự kiện một transaction; thiếu cấu hình chỉ cảnh báo một lần mỗi instance. */
    @Scheduled(fixedDelayString = "${app.notification.poll-ms:5000}")
    public void run() {
        if (!delivery.configured()) {
            if (configWarning.compareAndSet(false, true)) {
                log.warn("Chưa cấu hình OAuth thông báo; outbox giữ pending, cần kiểm tra NOTIFICATION_* env");
            }
            return;
        }
        configWarning.set(false);
        for (int i = 0; i < 10; i++) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> processOne()))) { break; }
        }
    }

    private boolean processOne() {
        var rows = jdbc.queryForList("SELECT * FROM commerce_notification_outbox WHERE NOT done "
                + "AND next_attempt_at<=CURRENT_TIMESTAMP ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if (rows.isEmpty()) { return false; }
        var row = rows.getFirst();
        UUID id = (UUID) row.get("id");
        try {
            delivery.send(Map.of("id", id, "userId", row.get("user_id"), "orderId", row.get("order_id"),
                    "orderNo", row.get("order_no"), "type", row.get("event_type"), "amount", row.get("amount"),
                    "occurredAt", ((Timestamp) row.get("occurred_at")).toInstant().toString()));
            jdbc.update("UPDATE commerce_notification_outbox SET done=true,attempts=attempts+1 WHERE id=?", id);
        } catch (RuntimeException error) {
            // Không ghi payload/token/HTTP body vào log; lỗi 4xx cấu hình cũng giữ để vận hành sửa rồi retry.
            jdbc.update("UPDATE commerce_notification_outbox SET attempts=attempts+1, "
                    + "next_attempt_at=CURRENT_TIMESTAMP+least(attempts+1,60)*interval '10 seconds' WHERE id=?", id);
            log.warn("Gửi thông báo chưa thành công: event={}, order={}, errorType={}; giữ outbox để retry",
                    id, row.get("order_id"), error.getClass().getSimpleName());
        }
        return true;
    }
}
