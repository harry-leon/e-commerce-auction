package com.nexus.catalog.entity;

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
        var category = new Category();
        category.setName("Danh mục ERD");
        entities.persist(category);
        var product = new Product();
        product.setSellerId(UUID.randomUUID());
        product.setCategory(category);
        product.setName("Sản phẩm ERD");
        entities.persist(product);
        var review = new ProductReview();
        review.setProduct(product);
        review.setUserId(UUID.randomUUID());
        review.setOrderId(UUID.randomUUID());
        review.setScore(5);
        review.setSellerRepliedAt(expected);
        entities.persist(review);
        entities.flush();
        entities.clear();
        // Kiểm tra cả giá trị vật lý, tránh round-trip cùng sai offset che khuất lỗi lưu timestamp UTC.
        assertEquals("2026-10-27 02:03:04.123456", jdbc.queryForObject(
                "SELECT seller_replied_at::text FROM product_reviews WHERE id = ?", String.class, review.getId()));
        assertEquals(expected, entities.find(ProductReview.class, review.getId()).getSellerRepliedAt());
        assertEquals("Danh mục ERD", entities.find(Category.class, category.getId()).getName());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'categories' AND column_name = 'name_vi'", Integer.class));
    }
}
