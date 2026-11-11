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
        assertEquals(sku.getId(), response.skus().get(0).id());
        assertEquals(new BigDecimal("99.99"), response.skus().get(0).originalPrice());
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
                null, "{\"size\":\"L\"}",
                new BigDecimal("50.00"), SkuStatus.ACTIVE);

        Sku sku = mapper.toSkuEntity(request);

        assertNotNull(sku);
        assertNull(sku.getId());
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
        UUID existingId = UUID.randomUUID();
        Sku sku = new Sku();
        sku.setId(existingId);
        sku.setOriginalPrice(new BigDecimal("10.00"));

        SkuRequest request = new SkuRequest(
                existingId, "{\"color\":\"red\"}",
                new BigDecimal("25.00"), SkuStatus.INACTIVE);

        mapper.updateSkuFromRequest(request, sku);

        assertEquals(existingId, sku.getId());
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

    /** Bảo vệ lỗi PostgreSQL 500 jsonb khi FE/Swagger gửi thuộc tính biến thể dạng chuỗi thuần thay vì JSON object. */
    @Test
    void normalizeAndFormatAttributes_HandlesPlainTextAndJsonObject() {
        SkuRequest plainReq = new SkuRequest(
                null, "Obsidian Black / USB-C",
                new BigDecimal("150000"), SkuStatus.ACTIVE);
        Sku entity = mapper.toSkuEntity(plainReq);
        assertEquals("\"Obsidian Black / USB-C\"", entity.getAttributes());
        assertEquals("Obsidian Black / USB-C", mapper.toSku(entity).attributes());

        SkuRequest blankReq = new SkuRequest(
                null, "   ",
                new BigDecimal("150000"), SkuStatus.ACTIVE);
        assertNull(mapper.toSkuEntity(blankReq).getAttributes());

        Sku jsonSku = new Sku();
        jsonSku.setId(UUID.randomUUID());
        jsonSku.setOriginalPrice(new BigDecimal("150000"));
        jsonSku.setStatus(SkuStatus.ACTIVE);
        jsonSku.setAttributes("{\"color\":\"Black\"}");
        assertEquals("{\"color\":\"Black\"}", mapper.toSku(jsonSku).attributes());
    }
}
