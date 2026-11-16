package com.nexus.commerce.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Chạy Flyway và Hibernate validate trên PostgreSQL tạm; bảo vệ schema ERD và round-trip timestamp UTC. */
@DataJpaTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.postgresql.Driver", "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.cloud.config.enabled=false", "eureka.client.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class FinalErdPostgresTest {
    @Autowired private EntityManager entities;
    @Autowired private JdbcTemplate jdbc;

    /** Dữ liệu mới phải ghi/đọc được sau migration; microsecond được giữ mà không lệch giờ JVM. */
    @Test
    void persistsAgainstFinalErdWithUtcTimestamps() {
        Instant expected = Instant.parse("2026-10-27T02:03:04.123456Z");
        var cart = Cart.builder().userId(UUID.randomUUID()).expiresAt(expected).build();
        entities.persist(cart);
        entities.flush();
        entities.clear();
        // Kiểm tra cả giá trị vật lý, tránh round-trip cùng sai offset che khuất lỗi lưu timestamp UTC.
        assertEquals("2026-10-27 02:03:04.123456", jdbc.queryForObject(
                "SELECT expires_at::text FROM carts WHERE id = ?", String.class, cart.getId()));
        assertEquals(expected, entities.find(Cart.class, cart.getId()).getExpiresAt());
        // Chỉ kiểm tra schema đang dùng; database test có thể đồng thời chứa schema health/migration độc lập.
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name = 'carts' AND column_name = 'created_at'", Integer.class));
        assertEquals(30, jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=current_schema() AND table_name = 'vouchers' AND column_name = 'name'", Integer.class));
    }
}
