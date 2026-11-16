package com.nexus.commerce.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.client.NotificationDelivery;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Giao ORDER_SHIPPED/ORDER_DELIVERED qua inbox/push sau commit, không gọi email/carrier hoặc cập nhật tài chính. */
@Component
public class ShippingNotificationWorker {
    private final JdbcTemplate jdbc;
    private final NotificationDelivery delivery;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShippingNotificationWorker(JdbcTemplate jdbc, NotificationDelivery delivery, PlatformTransactionManager manager,
            @Value("${app.shipping.notification-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.delivery = delivery; this.transaction = new TransactionTemplate(manager); this.enabled = enabled;
    }

    /** Chỉ bật sau consumer Notification !158; feature tắt hoặc OAuth thiếu giữ backlog, mỗi tick tối đa mười dòng. */
    @Scheduled(fixedDelayString = "${app.shipping.notification-delay-ms:10000}", scheduler = "shippingNotificationScheduler")
    public void run() {
        if (!enabled || !delivery.configured()) { return; }
        jdbc.queryForList("SELECT id FROM shipping_notification_outbox WHERE status='PENDING' "
                + "AND next_attempt_at<=now() ORDER BY next_attempt_at,id LIMIT 10", UUID.class).forEach(this::deliver);
    }

    /**
     * SKIP LOCKED giữ riêng row thông báo trong HTTP timeout hữu hạn; không khóa Order/Payment hoặc gửi lại shipment.
     * Mất ACK/crash gửi lại eventId/payload cố định để Notification khử trùng; tám lỗi đưa DEAD_LETTER, không xóa lịch sử.
     */
    public void deliver(UUID id) {
        if (!enabled || !delivery.configured()) { return; }
        transaction.executeWithoutResult(tx -> {
            var rows = jdbc.queryForList("SELECT *,payload::text AS snapshot FROM shipping_notification_outbox WHERE id=? "
                    + "AND status='PENDING' AND next_attempt_at<=now() FOR UPDATE SKIP LOCKED", id);
            if (rows.isEmpty()) { return; }
            var row = rows.getFirst();
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            String failure;
            try {
                Map<String, Object> payload = mapper.readValue((String) row.get("snapshot"), new TypeReference<>() { });
                // Không gửi nhầm snapshot của order/type khác hoặc dùng sự kiện kho/refund qua transport Shipping.
                if (!id.toString().equals(payload.get("id")) || !row.get("order_id").toString().equals(payload.get("orderId"))
                        || !row.get("event_type").equals(payload.get("type"))
                        || !java.util.Set.of("ORDER_SHIPPED", "ORDER_DELIVERED").contains(payload.get("type"))) {
                    throw new IllegalArgumentException("Invalid snapshot");
                }
                UUID.fromString((String) payload.get("userId")); Instant.parse((String) payload.get("occurredAt"));
                delivery.sendInbox(payload);
                jdbc.update("UPDATE shipping_notification_outbox SET status='DELIVERED',delivered_at=now(),last_error_type=NULL WHERE id=?", id);
                return;
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | NullPointerException error) {
                attempts = 8; failure = "INVALID_SNAPSHOT";
            } catch (RuntimeException error) { failure = error.getClass().getSimpleName(); }
            // Lỗi chỉ lưu class/phân loại, không lưu message/body/token của Notification.
            jdbc.update("UPDATE shipping_notification_outbox SET attempts=?,status=?,last_error_type=?,"
                    + "next_attempt_at=now()+(? * interval '1 second') WHERE id=?", attempts,
                    attempts >= 8 ? "DEAD_LETTER" : "PENDING", failure, Math.min(300, 1 << Math.min(attempts, 8)), id);
        });
    }
}
