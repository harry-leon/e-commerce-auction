package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.common.exception.ApiException;
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
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL thật kiểm tra key/khóa/audit/rollback, MVC riêng bảo vệ quyền; không gọi Commerce hoặc carrier thật. */
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
class ShippingEventReplayPostgresTest {
    private String schema; private JdbcTemplate jdbc; private TransactionTemplate tx; private ShippingEventReplayService service;
    /** Schema UUID riêng, Flyway thật và transaction wrapper như proxy production cho test JDBC độc lập. */
    @BeforeEach
    void setup() {
        schema = "event_replay_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("FULFILLMENT_TEST_DB_URL");
        Flyway.configure().dataSource(url, "postgres", "").schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "postgres", "");
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new DataSourceTransactionManager(source)); service = new ShippingEventReplayService(jdbc);
    }
    /** Chỉ dọn schema do test cấp, không đụng dữ liệu demo/live. */
    @AfterEach
    void cleanup() { if (jdbc != null) { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); } }

    /** Replay giữ snapshot/audit cũ kể cả đã ACK, khác nội dung bị 409 và chỉ một audit được tạo. */
    @Test
    void replayIsIdempotentAndPreservesSnapshot() {
        UUID id = fixture("SHIPMENT", "ShipmentDelivered"); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID();
        String payload = jdbc.queryForObject("SELECT payload::text FROM fulfillment_event_outbox WHERE id=?", String.class, id);
        UUID audit = replay(id, actor, key, "Đã sửa IAM");
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM fulfillment_event_outbox WHERE id=?", Integer.class, id));
        assertEquals(payload, jdbc.queryForObject("SELECT payload::text FROM fulfillment_event_outbox WHERE id=?", String.class, id));
        jdbc.update("UPDATE fulfillment_event_outbox SET done=true WHERE id=?", id);
        assertEquals(audit, replay(id, actor, key, "Đã sửa IAM"));
        assertThrows(ApiException.class, () -> replay(id, actor, key, "Nội dung khác"));
        assertThrows(ApiException.class, () -> replay(id, actor, UUID.randomUUID(), "Đã sửa IAM"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipping_event_replays WHERE event_id=?", Integer.class, id));
    }

    /** Rollback không reset row hoặc rò audit; event kho/không tồn tại/lý do rỗng đều không được replay Shipping. */
    @Test
    void rollbackAndScopeChecksPreserveBacklog() {
        UUID id = fixture("SHIPMENT", "ShipmentDelivered");
        tx.executeWithoutResult(status -> { service.replay(id, UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra"); status.setRollbackOnly(); });
        assertEquals(8, jdbc.queryForObject("SELECT attempts FROM fulfillment_event_outbox WHERE id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipping_event_replays", Integer.class));
        UUID inventory = fixture("INVENTORY", "InventoryInboundCompleted");
        assertThrows(ApiException.class, () -> replay(inventory, UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra"));
        assertThrows(ApiException.class, () -> replay(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra"));
        assertThrows(ApiException.class, () -> replay(id, UUID.randomUUID(), UUID.randomUUID(), " "));
    }

    /** Hai Admin cùng dead letter chỉ một request reset thành công, không nhân bản audit. */
    @Test
    void concurrentAdminsResetOnlyOnce() throws Exception {
        UUID id = fixture("SHIPMENT", "ShipmentDelivered"); CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Boolean> action = () -> {
                start.await();
                try { replay(id, UUID.randomUUID(), UUID.randomUUID(), "Đã kiểm tra"); return true; }
                catch (ApiException conflict) { return false; }
            };
            var first = pool.submit(action); var second = pool.submit(action); start.countDown();
            assertEquals(1, (first.get(5, TimeUnit.SECONDS) ? 1 : 0) + (second.get(5, TimeUnit.SECONDS) ? 1 : 0));
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM shipping_event_replays WHERE event_id=?", Integer.class, id));
    }
    private UUID replay(UUID id, UUID actor, UUID key, String reason) { return tx.execute(status -> service.replay(id, actor, key, reason)); }
    /** Dữ liệu dead letter giả lập lỗi transport, không chứng minh đã gửi hoặc giao carrier. */
    private UUID fixture(String aggregate, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fulfillment_event_outbox(id,aggregate_type,aggregate_id,event_type,payload,done,attempts,next_attempt_at) "
                + "VALUES (?,?,?,?,?::jsonb,false,8,CURRENT_TIMESTAMP)", id, aggregate, UUID.randomUUID(), type, "{\"fixture\":true}");
        return id;
    }
}
