package com.nexus.fulfillment.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fulfillment.client.CommerceShippingClient;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Giao riêng sáu event shipment sang Commerce sau commit; không chạm event kho hoặc gọi lại carrier. */
@Component
public class ShippingEventDeliveryWorker {
    private static final Set<String> TYPES = Set.of("ShipmentCreated", "ShipmentPickedUp", "ShipmentInTransit",
            "ShipmentDelivered", "ShipmentFailed", "ShipmentReturned");
    private final JdbcTemplate jdbc;
    private final CommerceShippingClient client;
    private final TransactionTemplate transaction;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShippingEventDeliveryWorker(JdbcTemplate jdbc, CommerceShippingClient client, PlatformTransactionManager manager,
            @Value("${app.shipping.commerce.events-enabled:false}") boolean enabled) {
        this.jdbc = jdbc; this.client = client; this.transaction = new TransactionTemplate(manager); this.enabled = enabled;
    }

    /** Mặc định tắt; thiếu OAuth không query/đánh dấu done. Mỗi tick tối đa mười dòng, scheduler riêng với HTTP timeout hữu hạn. */
    @Scheduled(fixedDelayString = "${app.shipping.commerce.events-delay-ms:10000}", scheduler = "shipmentEventScheduler")
    public void run() {
        if (!enabled || !client.configured()) { return; }
        var ids = jdbc.queryForList("SELECT id FROM fulfillment_event_outbox WHERE aggregate_type='SHIPMENT' AND done=false "
                + "AND attempts<8 AND next_attempt_at<=CURRENT_TIMESTAMP AND event_type IN "
                + "('ShipmentCreated','ShipmentPickedUp','ShipmentInTransit','ShipmentDelivered','ShipmentFailed','ShipmentReturned') "
                + "ORDER BY next_attempt_at,id LIMIT 10", UUID.class);
        ids.forEach(this::deliver);
    }

    /**
     * Khóa riêng outbox bằng SKIP LOCKED trong HTTP, không khóa shipment/Order; consumer Commerce khử trùng khi mất ACK/crash.
     * attempts>=8 và done=false là dead letter, không tự xóa hoặc gửi tiếp. Chỉ lưu class/status lỗi, không lưu body/message.
     */
    public void deliver(UUID id) {
        if (!enabled || !client.configured()) { return; }
        transaction.executeWithoutResult(tx -> {
            var rows = jdbc.queryForList("SELECT * FROM fulfillment_event_outbox WHERE id=? AND aggregate_type='SHIPMENT' "
                    + "AND done=false AND attempts<8 AND next_attempt_at<=CURRENT_TIMESTAMP FOR UPDATE SKIP LOCKED", id);
            if (rows.isEmpty() || !TYPES.contains(rows.getFirst().get("event_type"))) { return; }
            var row = rows.getFirst();
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            String failure;
            try {
                var payload = mapper.readValue(row.get("payload").toString(), new TypeReference<Map<String, Object>>() { });
                // Không để metadata DB/payload lệch làm một event kho hoặc order khác bị ACK nhầm.
                if (!id.toString().equals(payload.get("eventId")) || !row.get("aggregate_id").toString().equals(payload.get("shipmentId"))
                        || !row.get("event_type").equals(payload.get("eventType"))) { throw new IllegalArgumentException("Invalid snapshot"); }
                UUID.fromString((String) payload.get("orderId")); Instant.parse((String) payload.get("occurredAt"));
                client.sendEvent(payload);
                jdbc.update("UPDATE fulfillment_event_outbox SET done=true,published_at=CURRENT_TIMESTAMP,last_error=NULL WHERE id=?", id);
                return;
            } catch (CommerceShippingClient.DeliveryFailure error) {
                // 409 có thể là Delivered đến trước handover: retry hữu hạn, sau đó giữ để operator phân loại dependency/poison.
                if (error.status() == 400 || error.status() == 422) { attempts = 8; }
                failure = "HTTP_" + error.status();
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | NullPointerException error) {
                attempts = 8; failure = "INVALID_SNAPSHOT";
            } catch (RuntimeException error) { failure = error.getClass().getSimpleName(); }
            jdbc.update("UPDATE fulfillment_event_outbox SET attempts=?,last_error=?,next_attempt_at="
                    + "CURRENT_TIMESTAMP+(? * interval '1 second') WHERE id=?", attempts, failure,
                    Math.min(300, 1 << Math.min(attempts, 8)), id);
        });
    }
}
