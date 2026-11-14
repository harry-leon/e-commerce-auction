package com.nexus.commerce.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Chạy migration mới và nâng V3/V9/V10/V11 lên V12 trên schema riêng; giữ giá lịch sử, snapshot, đối soát COD và outbox timeout đấu giá.
 * Chỉ dùng NEXUS_TEST_DB_URL của PostgreSQL tạm; finally chỉ dọn schema UUID do test tạo.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class BaselineMigrationPostgresTest {
    /** V12 chỉ đổi constraint của schema đích, kể cả database đã có schema Commerce khác nâng lên V12. */
    @Test
    void timeoutMigrationResolvesConstraintInTargetSchemaOnly() {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String upgraded = "upgraded_" + UUID.randomUUID().toString().replace("-", "");
        String target = "target_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        try {
            migrate(url, upgraded, "latest").migrate();
            migrate(url, target, "11").migrate();
            migrate(url, target, "latest").migrate();
            for (String schema : new String[] {upgraded, target}) {
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_constraint "
                        + "WHERE conrelid=?::regclass AND conname='chk_commerce_notification_event_type'",
                        Integer.class, schema + ".commerce_notification_outbox"));
            }
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + target + " CASCADE");
            jdbc.execute("DROP SCHEMA IF EXISTS " + upgraded + " CASCADE");
        }
    }

    /** Nâng cấp không được đổi tiền/trạng thái đơn cũ hoặc phát lại thông báo lịch sử. */
    @ParameterizedTest
    @ValueSource(ints = {0, 3, 9, 10, 11})
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
                if (previous >= 10) {
                    // V10 đã bắt buộc snapshot/ref kho; fixture lịch sử dùng amount cũ, không lấy giá mới.
                    jdbc.update("INSERT INTO " + schema + ".payments "
                            + "(id,order_id,provider,method,amount,status,inventory_ref_id,price_snapshot) "
                            + "VALUES (?,?,'VNPAY','VNPAY',50000,'SUCCESS',?,"
                            + "jsonb_build_object('subtotal',50000,'shippingFee',0,'discount',0,'total',50000,'items','[]'::jsonb))",
                            payment, order, order);
                } else {
                    jdbc.update("INSERT INTO " + schema + ".payments "
                    + "(id,order_id,provider,method,amount,status) "
                    + "VALUES (?,?,'VNPAY','VNPAY',50000,'SUCCESS')", payment, order);
                }
            }
            var baseline = migrate(url, schema, "latest");
            baseline.migrate();
            // V13 chỉ thêm yêu cầu refund; nâng từ schema cũ vẫn phải giữ nguyên tiền/đơn và không tạo tác vụ giả.
            // V14 không duyệt hay gửi refund cũ; attempts ban đầu phải rỗng sau nâng cấp.
            // V15 thêm audit thủ công/receipt registry, không sinh quyết định cho dữ liệu cũ.
            // V16 chỉ phát sự kiện cho thay đổi mới, không gửi lại refund lịch sử khi nâng schema.
            // V17 thêm audit replay rỗng, không reset backlog lịch sử khi nâng cấp.
            // V18 không tự tạo inbox/binding/notification shipment cho dữ liệu lịch sử.
            // V19 chỉ bổ sung delivery state, không tạo notification Shipping cho đơn lịch sử.
            // V20 thêm audit replay Shipping rỗng, không tự reset backlog.
            assertEquals("20", baseline.info().current().getVersion().getVersion());
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".shipping_notification_replays", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".shipping_event_inbox", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".refund_notification_replays", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".refund_notification_outbox", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".refund_manual_resolutions", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".refund_attempts", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".refund_requests", Integer.class));
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
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyCodTasksUpgradeWithoutLeavingUnconfirmedOrdersReadyToShip(boolean completed) {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "cod_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID order = UUID.randomUUID();
        try {
            migrate(url, schema, "10").migrate();
            jdbc.update("INSERT INTO " + schema + ".orders "
                    + "(id,order_no,buyer_id,seller_id,source_type,status,payment_method,subtotal,total,shipping_address) "
                    + "VALUES (?,?,?,?, 'REGULAR','PAID','COD',50000,50000,'Legacy COD')",
                    order, "COD-" + order, UUID.randomUUID(), UUID.randomUUID());
            jdbc.update("INSERT INTO " + schema + ".payment_inventory_tasks(id,order_id,target_id,action,done) "
                    + "VALUES (?,?,?,'CONFIRM',?)", UUID.randomUUID(), order, UUID.randomUUID(), completed);
            migrate(url, schema, "latest").migrate();
            assertEquals(completed ? "PAID" : "PENDING_PAYMENT", jdbc.queryForObject(
                    "SELECT status FROM " + schema + ".orders WHERE id=?", String.class, order));
            assertEquals(completed ? null : "PENDING", jdbc.queryForObject(
                    "SELECT fulfillment_status FROM " + schema + ".orders WHERE id=?", String.class, order));
            assertEquals(new BigDecimal("50000.00"), jdbc.queryForObject(
                    "SELECT total FROM " + schema + ".orders WHERE id=?", BigDecimal.class, order));
            assertEquals(completed ? 0 : 1, jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".payment_inventory_tasks WHERE order_id=? AND action='COD_COMMIT'", Integer.class, order));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".payment_inventory_tasks WHERE order_id=? AND action='CONFIRM' AND NOT done", Integer.class, order));
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
