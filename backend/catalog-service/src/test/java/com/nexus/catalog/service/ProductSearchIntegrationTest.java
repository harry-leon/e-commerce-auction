package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nexus.catalog.dto.mapper.ProductMapperImpl;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.catalog.repository.ProductRepository;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

/** Chạy truy vấn JPA thật trên H2 chế độ PostgreSQL; kiểm tra ghép bộ lọc, danh mục con và escape LIKE. Không thay smoke PostgreSQL thật. */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:search;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProductSearchService.class, ProductMapperImpl.class})
class ProductSearchIntegrationTest {
    @Autowired
    private EntityManager entities;
    @Autowired
    private ProductSearchService search;
    @Autowired
    private ProductRepository products;

    private Category category(Category parent) {
        Category result = new Category();
        result.setName("Category");
        result.setParent(parent);
        result.setLevel(parent == null ? 1 : parent.getLevel() + 1);
        entities.persist(result);
        return result;
    }

    private Product product(Category category, String name, int price, ProductStatus status) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("Description");
        product.setCategory(category);
        product.setSellerId(UUID.randomUUID());
        product.setStatus(status);
        Sku sku = new Sku();
        sku.setProduct(product);
        sku.setSkuCode(UUID.randomUUID().toString());
        sku.setPrice(BigDecimal.valueOf(price));
        product.getSkus().add(sku);
        entities.persist(product);
        return product;
    }

    @Test
    void searchesGrandchildCategoriesByKeywordAndSkuPrice() {
        Category root = category(null);
        Category leaf = category(category(root));
        Product match = product(leaf, "Laptop Pro", 500, ProductStatus.ACTIVE);
        product(leaf, "Laptop Draft", 400, ProductStatus.DRAFT);
        product(leaf, "Laptop Expensive", 900, ProductStatus.ACTIVE);
        product(category(null), "Laptop Elsewhere", 500, ProductStatus.ACTIVE);
        entities.flush();
        var result = search.search("LAPTOP", root.getId(), BigDecimal.valueOf(300), BigDecimal.valueOf(600),
                false, null, PageRequest.of(0, 12));
        assertEquals(1, result.getTotalElements());
        assertEquals(match.getId(), result.getContent().getFirst().id());
        assertEquals(3, search.search(null, null, null, null, null, null, PageRequest.of(0, 2)).getTotalElements());
        assertEquals(3, products.countByCategoryId(match.getCategory().getId()));
    }

    @Test
    void treatsSearchWildcardsAsLiteralText() {
        Category root = category(null);
        product(root, "100% cotton", 10, ProductStatus.ACTIVE);
        product(root, "Cotton shirt", 10, ProductStatus.ACTIVE);
        entities.flush();
        assertEquals(1, search.search("%", null, null, null, null, null, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void rejectsInvalidPriceRange() {
        assertThrows(BusinessException.class, () -> search.search(null, null, BigDecimal.TEN,
                BigDecimal.ONE, null, null, PageRequest.of(0, 10)));
        assertThrows(BusinessException.class, () -> search.search(null, null, BigDecimal.valueOf(-1),
                null, null, null, PageRequest.of(0, 10)));
    }
}
