package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentRequest;
import com.nexus.fulfillment.shipping.adapter.CarrierCreateShipmentResult;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL riêng chứng minh dấu gửi độc lập rollback, chặn concurrent/restart; supplier chỉ mô phỏng HTTP carrier. */
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
class ShipmentDispatchPostgresTest {
    private String schema;
    private JdbcTemplate jdbc;
    private TransactionTemplate outer;
    private ShipmentDispatchGuard guard;
    private DataSourceTransactionManager manager;
    private final CarrierCreateShipmentRequest request = new CarrierCreateShipmentRequest(UUID.randomUUID(), UUID.randomUUID(),
            "Kho test", "Người nhận", "0900000000", "Địa chỉ test", 12, "WARD", 725, BigDecimal.ZERO,
            BigDecimal.TEN, "STANDARD", "Test");

    /** Migration chạy trong schema UUID độc lập, không dùng dữ liệu demo hoặc schema môi trường đang chạy. */
    @BeforeEach
    void setup() {
        schema = "dispatch_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("FULFILLMENT_TEST_DB_URL");
        Flyway.configure().dataSource(url, "postgres", "").schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, "postgres", "");
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        outer = new TransactionTemplate(manager);
        guard = new ShipmentDispatchGuard(jdbc, manager);
    }

    /** Chỉ xóa schema do test này cấp, không xóa volume hoặc dữ liệu service khác. */
    @AfterEach
    void cleanup() { if (jdbc != null) { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); } }

    /** Carrier thành công nhưng lưu shipment rollback vẫn giữ tracking, instance mới không được gọi carrier lần hai. */
    @Test
    void rollbackAfterCarrierAckRetainsTrackingAndBlocksRestart() {
        outer.executeWithoutResult(tx -> { guard.send("GHN", request, this::ack); tx.setRollbackOnly(); });
        assertEquals("UNKNOWN", state());
        assertEquals("TRACK-TEST", jdbc.queryForObject("SELECT tracking_number FROM shipment_dispatch_attempts", String.class));
        var restarted = new ShipmentDispatchGuard(jdbc, manager);
        AtomicInteger calls = new AtomicInteger();
        assertThrows(ApiException.class, () -> outer.executeWithoutResult(tx -> restarted.send("GHN", request, () -> {
            calls.incrementAndGet(); return ack();
        })));
        assertEquals(0, calls.get());
    }

    /** Chỉ outer transaction commit kèm ACK hợp lệ mới COMMITTED; gọi không có transaction dừng trước dấu gửi. */
    @Test
    void recordsCommittedOnlyAfterOuterCommit() {
        assertThrows(IllegalStateException.class, () -> guard.send("GHN", request, this::ack));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM shipment_dispatch_attempts", Integer.class));
        outer.executeWithoutResult(tx -> guard.send("GHN", request, this::ack));
        assertEquals("COMMITTED", state());
    }

    /** Caller nuốt timeout rồi commit cũng không được giả thành công; lỗi chỉ giữ tên class, không lưu thông điệp. */
    @Test
    void swallowedNetworkFailureStillRemainsUnknown() {
        outer.executeWithoutResult(tx -> assertThrows(IllegalStateException.class,
                () -> guard.send("GHN", request, () -> { throw new IllegalStateException("Sensitive upstream text"); })));
        assertEquals("UNKNOWN", state());
        assertEquals("IllegalStateException", jdbc.queryForObject("SELECT last_error_type FROM shipment_dispatch_attempts", String.class));
    }

    /** Unique order commit trước HTTP bảo vệ cả request khác key/instance; không giữ khóa attempt trong suốt HTTP. */
    @Test
    void concurrentRequestCannotEnterCarrierWhileFirstWaitsForAck() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> outer.executeWithoutResult(tx -> guard.send("GHN", request, () -> {
                calls.incrementAndGet(); entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Fixture timeout"); } }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
                return ack();
            })));
            try {
                assertEquals(true, entered.await(10, TimeUnit.SECONDS));
                assertThrows(ApiException.class, () -> outer.executeWithoutResult(tx -> guard.send("GHN", request, () -> {
                    calls.incrementAndGet(); return ack();
                })));
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, calls.get());
    }

    private String state() { return jdbc.queryForObject("SELECT status FROM shipment_dispatch_attempts", String.class); }

    /** Lifecycle lock buộc operator chờ transaction dispatch kết thúc, không khôi phục song song khi HTTP chưa trả ACK. */
    @Test
    void lifecycleLockBlocksResolutionUntilDispatchFinishes() throws Exception {
        CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var dispatch = pool.submit(() -> outer.executeWithoutResult(tx -> {
                guard.send("GHN", request, () -> {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("Test timeout"); } }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
                    return ack();
                });
                tx.setRollbackOnly();
            }));
            try {
                assertEquals(true, entered.await(10, TimeUnit.SECONDS));
                var resolving = pool.submit(() -> outer.execute(tx -> jdbc.queryForList(
                        "SELECT order_id FROM shipment_dispatch_locks WHERE order_id=? FOR UPDATE", request.orderId())));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                int waiting = 0;
                while (waiting == 0 && System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                            + "AND wait_event_type='Lock' AND query LIKE '%shipment_dispatch_locks%'", Integer.class);
                    if (waiting == 0) { Thread.sleep(20); }
                }
                assertEquals(true, waiting > 0);
                release.countDown();
                resolving.get(10, TimeUnit.SECONDS); dispatch.get(10, TimeUnit.SECONDS);
                assertEquals("UNKNOWN", state());
            } finally { release.countDown(); }
        }
    }
    /** ACK mock không gọi provider; tracking đủ để kiểm tra bằng chứng sau rollback. */
    private CarrierCreateShipmentResult ack() {
        return new CarrierCreateShipmentResult("GHN", "STANDARD", "TRACK-TEST", BigDecimal.ZERO, 1, null, "created", null);
    }
}
