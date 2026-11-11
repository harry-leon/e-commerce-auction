package com.nexus.catalog.dto.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.ProductImage;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductMapperTest {

    private final ProductMapper mapper = ProductMapper.INSTANCE;

    @Test
    void toResponse_MapsAllFieldsCorrectly() {
        Category category = new Category();
        category.setId(UUID.randomUUID());

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(UUID.randomUUID());
        product.setCategory(category);
        product.setName("Test Product");
        product.setDescription("Test Desc");
        product.setStatus(ProductStatus.DRAFT);
        product.setAuctionEnabled(true);
        product.setCreatedAt(Instant.now());

        Sku sku = new Sku();
        sku.setId(UUID.randomUUID());
        sku.setSkuCode("SKU-1");
        sku.setOriginalPrice(new BigDecimal("99.99"));
        sku.setStatus(SkuStatus.ACTIVE);
        product.getSkus().add(sku);

        ProductImage image = new ProductImage();
        image.setId(UUID.randomUUID());
        image.setUrl("https://example.com/pic.jpg");
        image.setSortOrder(1);
        product.getImages().add(image);

        ProductResponse response = mapper.toResponse(product);

        assertNotNull(response);
        assertEquals(product.getId(), response.id());
        assertEquals(product.getSellerId(), response.sellerId());
        assertEquals(category.getId(), response.categoryId());
        assertEquals("Test Product", response.name());
        assertEquals("Test Desc", response.description());
        assertEquals(ProductStatus.DRAFT, response.status());
        assertEquals(true, response.auctionEnabled());
        assertEquals(product.getCreatedAt(), response.createdAt());
        assertEquals(1, response.skus().size());
        assertEquals("SKU-1", response.skus().get(0).skuCode());
        assertEquals(1, response.images().size());
        assertEquals("https://example.com/pic.jpg", response.images().get(0).url());
    }

    @Test
    void toResponse_HandlesNullCategory() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("No Category Product");

        ProductResponse response = mapper.toResponse(product);

        assertNotNull(response);
        assertNull(response.categoryId());
    }

    @Test
    void toSkuEntity_MapsRequestCorrectly() {
        SkuRequest request = new SkuRequest(
                "SKU-NEW", "{\"size\":\"L\"}",
                new BigDecimal("50.00"), SkuStatus.ACTIVE);

        Sku sku = mapper.toSkuEntity(request);

        assertNotNull(sku);
        assertNull(sku.getId());
        assertEquals("SKU-NEW", sku.getSkuCode());
        assertEquals("{\"size\":\"L\"}", sku.getAttributes());
        assertEquals(new BigDecimal("50.00"), sku.getOriginalPrice());
        assertEquals(SkuStatus.ACTIVE, sku.getStatus());
    }

    @Test
    void toImageEntity_MapsRequestCorrectly() {
        ImageRequest request = new ImageRequest("https://example.com/img.jpg", 3);

        ProductImage image = mapper.toImageEntity(request);

        assertNotNull(image);
        assertNull(image.getId());
        assertEquals("https://example.com/img.jpg", image.getUrl());
        assertEquals(3, image.getSortOrder());
    }

    @Test
    void updateSkuFromRequest_UpdatesFields() {
        Sku sku = new Sku();
        sku.setId(UUID.randomUUID());
        sku.setSkuCode("OLD-CODE");
        sku.setOriginalPrice(new BigDecimal("10.00"));

        SkuRequest request = new SkuRequest(
                "UPDATED-CODE", "{\"color\":\"red\"}",
                new BigDecimal("25.00"), SkuStatus.INACTIVE);

        mapper.updateSkuFromRequest(request, sku);

        assertEquals("UPDATED-CODE", sku.getSkuCode());
        assertEquals("{\"color\":\"red\"}", sku.getAttributes());
        assertEquals(new BigDecimal("25.00"), sku.getOriginalPrice());
        assertEquals(SkuStatus.INACTIVE, sku.getStatus());
    }

    @Test
    void updateImageFromRequest_UpdatesFields() {
        ProductImage image = new ProductImage();
        image.setId(UUID.randomUUID());
        image.setUrl("https://old.url/1.png");
        image.setSortOrder(0);

        ImageRequest request = new ImageRequest("https://new.url/2.png", 5);

        mapper.updateImageFromRequest(request, image);

        assertEquals("https://new.url/2.png", image.getUrl());
        assertEquals(5, image.getSortOrder());
    }
}
