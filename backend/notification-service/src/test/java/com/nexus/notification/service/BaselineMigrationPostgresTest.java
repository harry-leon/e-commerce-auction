package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Kiểm tra schema mới/nâng V2, V4 lên V5; bảo toàn lịch sử email, thông báo Commerce và attempts cũ. */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class BaselineMigrationPostgresTest {
    /** V4 chỉ bỏ event_id trùng và V5 giữ nguyên toàn bộ bản ghi lịch sử lẫn attempts khi nâng cấp lên bản mới nhất. */
    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void createsBaselineAndPreservesEmailHistory(int previous) {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "baseline_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID sent = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        try {
            if (previous > 0) {
                migrate(url, schema, String.valueOf(previous)).migrate();
                for (UUID id : new UUID[] {sent, failed}) {
                    jdbc.update("INSERT INTO " + schema + ".notifications "
                        + "(id,user_id,event_id,channel,title,content,status) "
                        + "VALUES (?,?,'legacy-email','EMAIL','Legacy','Original content',?)",
                        id, UUID.randomUUID(), id.equals(sent) ? "SENT" : "FAILED");
                }
                jdbc.update("INSERT INTO " + schema + ".notification_attempts "
                    + "(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), sent);
            }
            var baseline = migrate(url, schema, "latest");
            baseline.migrate();
            assertEquals("5", baseline.info().current().getVersion().getVersion());
            assertEquals(0, baseline.migrate().migrationsExecuted);
            assertEquals(previous > 0 ? 2 : 0, jdbc.queryForObject("SELECT count(*) FROM "
                + schema + ".notifications", Integer.class));
            if (previous > 0) {
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".notifications WHERE event_id='legacy-email' AND id=? AND status='SENT'",
                    Integer.class, sent));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".notifications WHERE id=? AND event_id IS NULL AND status='FAILED'",
                    Integer.class, failed));
                assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM " + schema
                    + ".notification_attempts WHERE notification_id=?", Integer.class, sent));
            }
        } finally {
            // Chỉ xóa schema có UUID do ca test này tạo trong database tạm.
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Nâng cấp từ V4 lên V5 phải giữ nguyên toàn bộ bản ghi EMAIL/IN_APP và attempts; cột đấu giá mới mặc định null. */
    @Test
    void upgradesV4ToV5PreservingExistingNotifications() {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        String schema = "upgrade_v4_" + UUID.randomUUID().toString().replace("-", "");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "postgres", ""));
        UUID emailId = UUID.randomUUID();
        UUID inAppId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        try {
            migrate(url, schema, "4").migrate();
            jdbc.update("INSERT INTO " + schema + ".notifications "
                    + "(id,user_id,event_id,channel,title,content,status) "
                    + "VALUES (?,?,'email-v4-1','EMAIL','Legacy Email','Nội dung email V4','SENT')",
                    emailId, userId);
            jdbc.update("INSERT INTO " + schema + ".notifications "
                    + "(id,user_id,event_id,channel,title,content,status,order_id,event_type) "
                    + "VALUES (?,?,'inapp-v4-1','IN_APP','Đã tạo đơn hàng','Đơn ORD-V4','SENT',?,'ORDER_CREATED')",
                    inAppId, userId, orderId);
            jdbc.update("INSERT INTO " + schema + ".notification_attempts "
                    + "(id,notification_id,attempt_no,result) VALUES (?,?,1,'SUCCESS')",
                    UUID.randomUUID(), inAppId);

            var upgraded = migrate(url, schema, "latest");
            upgraded.migrate();
            assertEquals("5", upgraded.info().current().getVersion().getVersion());
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM " + schema + ".notifications", Integer.class));
            assertEquals("Legacy Email", jdbc.queryForObject(
                    "SELECT title FROM " + schema + ".notifications WHERE id=?", String.class, emailId));
            assertEquals("ORDER_CREATED", jdbc.queryForObject(
                    "SELECT event_type FROM " + schema + ".notifications WHERE id=?", String.class, inAppId));
            assertEquals(orderId, jdbc.queryForObject(
                    "SELECT order_id FROM " + schema + ".notifications WHERE id=?", UUID.class, inAppId));
            assertNull(jdbc.queryForObject(
                    "SELECT auction_id FROM " + schema + ".notifications WHERE id=?", UUID.class, inAppId));
            assertNull(jdbc.queryForObject(
                    "SELECT payment_link FROM " + schema + ".notifications WHERE id=?", String.class, inAppId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM " + schema + ".notification_attempts WHERE notification_id=?",
                    Integer.class, inAppId));
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Cô lập version history để kiểm tra đường upgrade không ảnh hưởng inbox của suite khác. */
    private Flyway migrate(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "postgres", "").schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
}
