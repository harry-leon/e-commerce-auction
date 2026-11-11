package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.catalog.config.CatalogProperties;
import com.nexus.catalog.dto.mapper.ProductMapperImpl;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.entity.CatalogOutboxEvent;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.CatalogOutboxEventRepository;
import com.nexus.catalog.service.impl.ProductServiceImpl;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Kiểm thử tích hợp JPA thực tế cho Catalog Service sau khi tách sku_code:
 * 1. Tạo sản phẩm tự cấp skuId UUID và ghi bản ghi outbox ProductSkuCreated trong cùng transaction.
 * 2. Cập nhật sản phẩm giữ nguyên skuId của SKU hiện có và chỉ ghi thêm ProductSkuCreated cho SKU mới thêm.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:catalog_outbox;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ProductServiceImpl.class,
        ProductMapperImpl.class,
        CatalogProperties.class,
        DynamicPricingService.class,
        CatalogEventOutboxService.class,
        ProductOutboxIntegrationTest.TestBeans.class
})
class ProductOutboxIntegrationTest {

    @TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        LocalValidatorFactoryBean validator() {
            return new LocalValidatorFactoryBean();
        }
    }

    @Autowired
    private EntityManager entities;

    @Autowired
    private ProductService productService;

    @Autowired
    private CatalogOutboxEventRepository outboxRepository;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAndUpdatePreserveExistingSkuIdAndWriteProductSkuCreatedOutbox() {
        UUID sellerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        sellerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SELLER"))));

        Category category = new Category();
        category.setName("Audio");
        category.setNameVi("Âm thanh");
        category.setLevel(1);
        entities.persist(category);
        entities.flush();

        ProductRequest createReq = new ProductRequest(
                "Studio Headphones",
                "Reference closed-back",
                category.getId(),
                false,
                List.of(new SkuRequest(null, "{\"color\":\"black\"}", new BigDecimal("1200000"), SkuStatus.ACTIVE)),
                List.of());

        ProductResponse created = productService.create(sellerId, createReq);
        entities.flush();

        assertEquals(1, created.skus().size());
        UUID preservedSkuId = created.skus().get(0).id();
        assertNotNull(preservedSkuId);

        List<CatalogOutboxEvent> afterCreate = outboxRepository.findAll();
        assertEquals(1, afterCreate.size());
        assertEquals(CatalogEventOutboxService.EVENT_PRODUCT_SKU_CREATED, afterCreate.get(0).getEventType());
        assertEquals(preservedSkuId, afterCreate.get(0).getAggregateId());
        assertTrue(afterCreate.get(0).getPayload().contains(preservedSkuId.toString()));

        ProductRequest updateReq = new ProductRequest(
                "Studio Headphones Gen 2",
                "Updated description",
                category.getId(),
                false,
                List.of(
                        new SkuRequest(preservedSkuId, "{\"color\":\"black\"}", new BigDecimal("1350000"), SkuStatus.ACTIVE),
                        new SkuRequest(null, "{\"color\":\"silver\"}", new BigDecimal("1400000"), SkuStatus.ACTIVE)),
                List.of());

        ProductResponse updated = productService.update(created.id(), updateReq);
        entities.flush();

        assertEquals(2, updated.skus().size());
        assertTrue(updated.skus().stream().anyMatch(s -> preservedSkuId.equals(s.id())
                && new BigDecimal("1350000").compareTo(s.originalPrice()) == 0));

        UUID secondSkuId = updated.skus().stream()
                .map(s -> s.id())
                .filter(id -> !preservedSkuId.equals(id))
                .findFirst()
                .orElseThrow();
        assertNotEquals(preservedSkuId, secondSkuId);

        List<CatalogOutboxEvent> afterUpdate = outboxRepository.findAll();
        assertEquals(2, afterUpdate.size());
        assertTrue(afterUpdate.stream().anyMatch(e -> secondSkuId.equals(e.getAggregateId())));
    }
}
