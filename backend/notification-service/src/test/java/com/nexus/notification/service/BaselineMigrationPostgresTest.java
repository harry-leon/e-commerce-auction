package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** TT-S2-1: xác minh schema mới/nâng V2 lên V4; bảo toàn email và attempts trước khi thêm khóa event. */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class BaselineMigrationPostgresTest {
    /** V4 chỉ bỏ event_id trùng; ưu tiên email SENT, giữ nguyên mọi bản ghi và lịch sử gửi. */
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
            assertEquals("4", baseline.info().current().getVersion().getVersion());
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

    /** Cô lập version history để kiểm tra đường upgrade không ảnh hưởng inbox của suite khác. */
    private Flyway migrate(String url, String schema, String target) {
        return Flyway.configure().dataSource(url, "postgres", "").schemas(schema)
            .defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
}
