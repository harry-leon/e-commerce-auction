package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.fulfillment.client.CommerceShippingClient;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL thật bảo vệ outbox ACK/retry/poison/concurrent; HTTP Commerce được mock, không gọi carrier. */
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
class ShippingEventDeliveryPostgresTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private CommerceShippingClient client;
    private ShippingEventDeliveryWorker worker;
    private String schema;

    /** Query health phải chỉ đếm backlog shipment chưa ACK, nhận diện cả dead letter lẫn pending quá tuổi trên PostgreSQL. */
    @Test
    void transportHealthTracksRealPendingAndDeadLetterRows() throws Exception {
        var health = new com.nexus.fulfillment.config.ShippingEventTransportHealth(jdbc, client, true, 300);
        UUID dead = fixture("SHIPMENT", "ShipmentDelivered");
        assertEquals(org.springframework.boot.actuate.health.Status.UP, health.health().getStatus());
        jdbc.update("UPDATE fulfillment_event_outbox SET attempts=8 WHERE id=?", dead);
        UUID old = fixture("SHIPMENT", "ShipmentCreated");
        jdbc.update("UPDATE fulfillment_event_outbox SET created_at=CURRENT_TIMESTAMP-interval '1 hour' WHERE id=?", old);
        assertEquals(org.springframework.boot.actuate.health.Status.OUT_OF_SERVICE, health.health().getStatus());
        assertEquals(1L, health.health().getDetails().get("deadLetters"));
        assertEquals(1L, health.health().getDetails().get("overdue"));
        jdbc.update("UPDATE fulfillment_event_outbox SET done=true WHERE id IN (?,?)", dead, old);
        assertEquals(org.springframework.boot.actuate.health.Status.UP, health.health().getStatus());
    }

    /** Schema UUID riêng cho từng test, Flyway thật; không dùng dữ liệu service đang chạy. */
    @BeforeEach
    void setup() {
        schema = "shipping_transport_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("FULFILLMENT_TEST_DB_URL");
        Flyway.configure().dataSource(url, "postgres", "").schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "postgres", "");
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        client = mock(CommerceShippingClient.class); when(client.configured()).thenReturn(true);
        worker = new ShippingEventDeliveryWorker(jdbc, client, manager, true);
    }
    /** Chỉ dọn schema test đã cấp, không xóa volume hoặc dữ liệu môi trường thật. */
    @AfterEach
    void cleanup() { if (jdbc != null) { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); } }

    /** Mất ACK giữ id/payload, backoff chặn retry ngay và success lần sau không nhân bản delivery tiếp. */
    @Test
    void retriesIdenticalSnapshotAfterLostAck() throws Exception {
        UUID event = fixture("SHIPMENT", "ShipmentDelivered");
        org.mockito.Mockito.doThrow(new CommerceShippingClient.DeliveryFailure(0)).doNothing().when(client).sendEvent(any());
        worker.deliver(event); worker.deliver(event);
        verify(client, times(1)).sendEvent(any());
        assertEquals(false, done(event));
        due(event); worker.deliver(event); worker.deliver(event);
        var captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).sendEvent(captor.capture());
        assertEquals(captor.getAllValues().getFirst(), captor.getAllValues().getLast());
        assertEquals(true, done(event));
    }

    /** 409 được retry hữu hạn để chờ dependency; 422 poison dừng ngay, event kho không bị worker shipment chiếm. */
    @Test
    void boundedRetriesAndPoisonDoNotConsumeInventoryEvents() throws Exception {
        UUID event = fixture("SHIPMENT", "ShipmentDelivered");
        doThrow(new CommerceShippingClient.DeliveryFailure(409)).when(client).sendEvent(any());
        for (int i = 0; i < 8; i++) { due(event); worker.deliver(event); }
        due(event); worker.deliver(event);
        verify(client, times(8)).sendEvent(any());
        assertEquals(8, attempts(event)); assertEquals(false, done(event));
        UUID poison = fixture("SHIPMENT", "ShipmentDelivered");
        doThrow(new CommerceShippingClient.DeliveryFailure(422)).when(client).sendEvent(any());
        worker.deliver(poison); assertEquals(8, attempts(poison));
        UUID inventory = fixture("INVENTORY", "InventoryInboundCompleted");
        worker.deliver(inventory); assertEquals(0, attempts(inventory)); assertEquals(false, done(inventory));
    }

    /** Hai instance cùng row chỉ một HTTP chạy; done chỉ ghi sau ACK, instance khác phải SKIP LOCKED ngay. */
    @Test
    void concurrentWorkersDoNotDeliverSameRow() throws Exception {
        UUID event = fixture("SHIPMENT", "ShipmentDelivered");
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test timeout"); }
            return null;
        }).when(client).sendEvent(any());
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> worker.deliver(event));
            try {
                assertEquals(true, entered.await(10, TimeUnit.SECONDS));
                pool.submit(() -> new ShippingEventDeliveryWorker(jdbc, client, manager, true).deliver(event)).get(3, TimeUnit.SECONDS);
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        verify(client, times(1)).sendEvent(any()); assertEquals(true, done(event));
    }

    /** Feature tắt/OAuth thiếu giữ nguyên backlog; payload lệch DB bị dead letter trước HTTP. */
    @Test
    void disabledMissingOauthAndMalformedSnapshotNeverSend() throws Exception {
        UUID event = fixture("SHIPMENT", "ShipmentDelivered");
        new ShippingEventDeliveryWorker(jdbc, client, manager, false).run();
        when(client.configured()).thenReturn(false); worker.run(); worker.deliver(event);
        assertEquals(0, attempts(event));
        when(client.configured()).thenReturn(true);
        jdbc.update("UPDATE fulfillment_event_outbox SET payload='{}'::jsonb WHERE id=?", event);
        worker.deliver(event); assertEquals(8, attempts(event));
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).sendEvent(any());
    }

    /** Snapshot fixture đúng envelope hiện tại của Fulfillment; không chứa PII/credential. */
    private UUID fixture(String aggregateType, String type) throws Exception {
        UUID id = UUID.randomUUID(); UUID shipment = UUID.randomUUID();
        // Envelope đầy đủ như FulfillmentEventOutboxService phát: schemaVersion/aggregateId/correlationId.
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("eventId", id); body.put("eventType", type); body.put("schemaVersion", 1);
        body.put("aggregateType", "SHIPMENT"); body.put("aggregateId", shipment); body.put("correlationId", id);
        body.put("shipmentId", shipment); body.put("orderId", UUID.randomUUID());
        body.put("occurredAt", "2026-11-13T00:00:00Z"); body.put("carrier", "GHN"); body.put("trackingNumber", "TRACK");
        body.put("status", "DELIVERED");
        String payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        jdbc.update("INSERT INTO fulfillment_event_outbox(id,aggregate_type,aggregate_id,event_type,payload,done,attempts,next_attempt_at) "
                + "VALUES (?,?,?,?,?::jsonb,false,0,CURRENT_TIMESTAMP-interval '1 second')", id, aggregateType, shipment, type, payload);
        return id;
    }
    private void due(UUID id) { jdbc.update("UPDATE fulfillment_event_outbox SET next_attempt_at=CURRENT_TIMESTAMP-interval '1 second' WHERE id=?", id); }
    private int attempts(UUID id) { return jdbc.queryForObject("SELECT attempts FROM fulfillment_event_outbox WHERE id=?", Integer.class, id); }
    private boolean done(UUID id) { return jdbc.queryForObject("SELECT done FROM fulfillment_event_outbox WHERE id=?", Boolean.class, id); }
}
