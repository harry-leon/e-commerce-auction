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
 * TT-S2-1: chạy migration mới và nâng V3 lên V7 trên schema test riêng.
 * Chỉ dùng NEXUS_TEST_DB_URL của PostgreSQL tạm; finally chỉ dọn schema UUID do test tạo.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class BaselineMigrationPostgresTest {
    /** Nâng cấp không được đổi tiền/trạng thái đơn cũ hoặc phát lại thông báo lịch sử. */
    @ParameterizedTest
    @ValueSource(ints = {0, 3})
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
            assertEquals("7", baseline.info().current().getVersion().getVersion());
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

    /** Schema sinh nội bộ cô lập lịch sử Flyway; không clean schema public của suite khác. */
    private Flyway migrate(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "postgres", "").schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
}
