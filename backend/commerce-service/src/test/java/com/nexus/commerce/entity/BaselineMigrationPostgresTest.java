package com.nexus.commerce.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Chạy migration mới và nâng V3/V9 lên V10 trên schema test riêng; giữ giá lịch sử và snapshot tiền đã gửi cổng.
 * Chỉ dùng NEXUS_TEST_DB_URL của PostgreSQL tạm; finally chỉ dọn schema UUID do test tạo.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class BaselineMigrationPostgresTest {
    /** Nâng cấp không được đổi tiền/trạng thái đơn cũ hoặc phát lại thông báo lịch sử. */
    @ParameterizedTest
    @ValueSource(ints = {0, 3, 9})
    void createsBaselineAndPreservesLegacyOrders(int previous) {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "baseline_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID order = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        try {
            if (previous > 0) {
                migrate(url, schema, String.valueOf(previous)).migrate();
                // Dữ liệu V3 có thật trước upgrade: không điền các cột V4–V7 chưa tồn tại.
                jdbc.update("INSERT INTO " + schema + ".orders "
                    + "(id,order_no,buyer_id,seller_id,source_type,status,total,shipping_address) "
                    + "VALUES (?,?,?,?, 'REGULAR','PAID',50000,'Legacy address')",
                    order, "LEGACY-" + order, UUID.randomUUID(), UUID.randomUUID());
                jdbc.update("INSERT INTO " + schema + ".payments "
                    + "(id,order_id,provider,method,amount,status) "
                    + "VALUES (?,?,'VNPAY','VNPAY',50000,'SUCCESS')", payment, order);
            }
            var baseline = migrate(url, schema, "latest");
            baseline.migrate();
            assertEquals("10", baseline.info().current().getVersion().getVersion());
            assertEquals(0, baseline.migrate().migrationsExecuted);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".commerce_notification_outbox", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema
                + ".payment_inventory_tasks", Integer.class));
            if (previous > 0) {
                assertEquals("PAID", jdbc.queryForObject("SELECT status FROM " + schema
                    + ".orders WHERE id=?", String.class, order));
                assertEquals(new BigDecimal("50000.00"), jdbc.queryForObject("SELECT amount FROM "
                    + schema + ".payments WHERE id=?", BigDecimal.class, payment));
                assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM " + schema
                    + ".payments WHERE id=?", String.class, payment));
                assertNull(jdbc.queryForObject("SELECT buyer_email FROM " + schema
                    + ".orders WHERE id=?", String.class, order));
                assertNull(jdbc.queryForObject("SELECT payment_url FROM " + schema
                    + ".payments WHERE id=?", String.class, payment));
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Pending V9 phải giữ snapshot giá gửi cổng nhưng chuyển official về null; không tạo outbox hoặc thanh toán mới. */
    @org.junit.jupiter.api.Test
    void pendingV9SnapshotsSurviveUpgradeWithoutFinalizingPrices() {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "pending_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID order = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        UUID item = UUID.randomUUID();
        try {
            migrate(url, schema, "9").migrate();
            jdbc.update("INSERT INTO " + schema + ".orders "
                    + "(id,order_no,buyer_id,seller_id,source_type,status,subtotal,total,shipping_address) "
                    + "VALUES (?,?,?,?, 'REGULAR','PENDING_PAYMENT',50000,50000,'Legacy pending')",
                    order, "OLD-" + order, UUID.randomUUID(), UUID.randomUUID());
            jdbc.update("INSERT INTO " + schema + ".order_items(id,order_id,sku_id,product_name,sku_code,quantity,unit_price) "
                    + "VALUES (?,?,?,'Áo','AO',2,25000)", item, order, UUID.randomUUID());
            jdbc.update("INSERT INTO " + schema + ".payments(id,order_id,provider,method,amount,status) "
                    + "VALUES (?,?,'VNPAY','VNPAY',50000,'PENDING')", payment, order);
            migrate(url, schema, "latest").migrate();
            assertNull(jdbc.queryForObject("SELECT total FROM " + schema + ".orders WHERE id=?", BigDecimal.class, order));
            assertNull(jdbc.queryForObject("SELECT unit_price FROM " + schema + ".order_items WHERE id=?", BigDecimal.class, item));
            assertEquals(false, jdbc.queryForObject("SELECT price_finalized FROM " + schema + ".orders WHERE id=?", Boolean.class, order));
            assertEquals(new BigDecimal("50000.00"), jdbc.queryForObject("SELECT estimated_total FROM " + schema + ".orders WHERE id=?", BigDecimal.class, order));
            assertEquals(new BigDecimal("50000.00"), jdbc.queryForObject("SELECT (price_snapshot->>'total')::numeric FROM "
                    + schema + ".payments WHERE id=?", BigDecimal.class, payment));
            assertEquals(order, jdbc.queryForObject("SELECT inventory_ref_id FROM " + schema + ".payments WHERE id=?", UUID.class, payment));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".payment_inventory_tasks", Integer.class));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Giá pending lệch phải chặn migration và giữ dữ liệu cũ để đối soát, không tự sửa thành số tiền khác. */
    @org.junit.jupiter.api.Test
    void inconsistentPendingPricesAbortUpgradeWithoutClearingOriginals() {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "invalid_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID order = UUID.randomUUID();
        try {
            migrate(url, schema, "9").migrate();
            jdbc.update("INSERT INTO " + schema + ".orders "
                    + "(id,order_no,buyer_id,seller_id,source_type,status,subtotal,total,shipping_address) "
                    + "VALUES (?,?,?,?, 'REGULAR','PENDING_PAYMENT',50000,50000,'Invalid pending')",
                    order, "INVALID-" + order, UUID.randomUUID(), UUID.randomUUID());
            jdbc.update("INSERT INTO " + schema + ".payments(id,order_id,provider,method,amount,status) "
                    + "VALUES (?,?,'VNPAY','VNPAY',60000,'PENDING')", UUID.randomUUID(), order);
            // Không đủ dòng/amount lệch: V10 phải rollback, official cũ còn nguyên để vận hành đối soát.
            org.junit.jupiter.api.Assertions.assertThrows(org.flywaydb.core.api.FlywayException.class,
                    () -> migrate(url, schema, "latest").migrate());
            assertEquals(new BigDecimal("50000.00"), jdbc.queryForObject(
                    "SELECT total FROM " + schema + ".orders WHERE id=?", BigDecimal.class, order));
            assertEquals("9", migrate(url, schema, "9").info().current().getVersion().getVersion());
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Schema sinh nội bộ cô lập lịch sử Flyway; không clean schema public của suite khác. */
    private Flyway migrate(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "postgres", "").schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
}
