package com.nexus.fulfillment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.common.exception.BusinessException;
import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.service.impl.InventoryServiceImpl;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL thật kiểm tra khóa giữa commitByOrder và sweeper; không mock repository hoặc tự giả lập unique/row lock.
 * Chỉ chạy với FULFILLMENT_TEST_DB_URL của DB test riêng; mỗi ca tạo UUID mới, không dùng dữ liệu demo/production.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${FULFILLMENT_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false", "spring.cloud.config.enabled=false",
        "spring.config.import=", "eureka.client.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({InventoryServiceImpl.class, InventoryPaymentRacePostgresTest.Config.class})
@EnabledIfEnvironmentVariable(named = "FULFILLMENT_TEST_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InventoryPaymentRacePostgresTest {
    @Autowired private InventoryService inventory;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    private UUID order;
    private UUID stock;
    private UUID reservation;

    @TestConfiguration
    static class Config {
        @Bean FulfillmentProperties fulfillmentProperties() {
            return new FulfillmentProperties();
        }
    }

    /** Một đơn winner giữ đúng một đơn vị đã tới hạn để cả commit và sweep đều có thể tranh khóa. */
    @BeforeEach
    void seed() {
        order = UUID.randomUUID();
        stock = UUID.randomUUID();
        reservation = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID sellerSkuId = UUID.randomUUID();
        jdbc.update("INSERT INTO warehouses(id,code,name,address) VALUES (?,?,?,?)", warehouse, warehouse.toString().substring(0, 20), "Test", "Test");
        jdbc.update("INSERT INTO seller_skus(id,seller_id,sku_id,sku_code,status) VALUES (?,?,?,?,'ACTIVE')",
                sellerSkuId, UUID.randomUUID(), skuId, "FSKU-TEST-" + sellerSkuId.toString().substring(0, 8));
        jdbc.update("INSERT INTO inventories(id,sku_id,seller_sku_id,warehouse_id,total_qty,reserved_qty) VALUES (?,?,?,?,1,1)",
                stock, skuId, sellerSkuId, warehouse);
        jdbc.update("INSERT INTO inventory_reservations(id,inventory_id,quantity,ref_type,ref_id,status,expires_at) "
                + "VALUES (?,?,1,'ORDER',?,'PENDING',(CURRENT_TIMESTAMP AT TIME ZONE 'UTC') - interval '1 minute')", reservation, stock, order);
    }

    /** Commit giữ khóa trước; sweep đọc ứng viên cũ rồi chờ phải thấy CONFIRMED sau commit, không ghi RELEASE. */
    @Test
    void commitWinsWhileSweepWaitsWithoutReleasingStalePendingEntity() throws Exception {
        var locked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var committing = pool.submit(() -> new TransactionTemplate(manager).execute(status -> {
                inventory.commitByOrder(order);
                locked.countDown();
                await(finish);
                return true;
            }));
            try {
                assertTrue(locked.await(10, TimeUnit.SECONDS));
                var sweeping = pool.submit(() -> inventory.autoReleaseExpiredReservations());
                awaitDatabaseLock();
                finish.countDown();
                committing.get(15, TimeUnit.SECONDS);
                sweeping.get(15, TimeUnit.SECONDS);
            } finally {
                finish.countDown();
            }
        }
        inventory.commitByOrder(order);
        inventory.releaseByOrder(order, "REPLAY_RELEASE");
        assertEquals("CONFIRMED", state());
        assertEquals(0, quantity("total_qty"));
        assertEquals(0, quantity("reserved_qty"));
        assertEquals(1, movements("OUTBOUND"));
        assertEquals(0, movements("RELEASE"));
    }

    /** Sweep giữ khóa trước; commit phải từ chối EXPIRED sau khi chờ, không trừ hàng đã được hoàn. */
    @Test
    void sweepWinsAndCommitCannotResurrectExpiredStock() throws Exception {
        inventory.autoReleaseExpiredReservations();
        var error = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class, () -> inventory.commitByOrder(order));
        assertEquals("INVENTORY_RESERVATION_EXPIRED", error.getCode());
        inventory.autoReleaseExpiredReservations();
        assertEquals("EXPIRED", state());
        assertEquals(1, quantity("total_qty"));
        assertEquals(0, quantity("reserved_qty"));
        assertEquals(0, movements("OUTBOUND"));
        assertEquals(1, movements("RELEASE"));
    }

    /** Chờ trạng thái khóa thật thay vì sleep cố định; timeout giúp test fail rõ nếu hai transaction không tranh nhau. */
    private void awaitDatabaseLock() throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            Integer count = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
                    + "AND wait_event_type='Lock' AND query LIKE '%inventory_reservations%'", Integer.class);
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Sweeper không chờ row lock của commit");
    }

    /** Giữ transaction commit đang mở cho đến khi sweeper đã chờ cùng row lock; không nuốt interruption. */
    private void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private String state() {
        return jdbc.queryForObject("SELECT status FROM inventory_reservations WHERE id=?", String.class, reservation);
    }

    /** Tên cột chỉ đến từ literal test, không nhận input bên ngoài; đọc connection mới để tránh cache JPA. */
    private int quantity(String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM inventories WHERE id=?", Integer.class, stock);
    }

    private int movements(String movement) {
        return jdbc.queryForObject("SELECT count(*) FROM inventory_ledger WHERE ref_id=? AND movement_type=?", Integer.class, order, movement);
    }
}
