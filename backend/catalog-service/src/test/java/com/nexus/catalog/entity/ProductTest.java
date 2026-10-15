package com.nexus.catalog.entity;

import com.nexus.catalog.enums.ProductStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private Product newValidProduct() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(UUID.randomUUID());
        product.setCategory(new Category());
        product.setName("iPhone 16");
        product.setDescription("Describe");
        product.setStatus(ProductStatus.DRAFT);
        product.setAuctionEnabled(false);
        return product;
    }

    @Test
    void defaultsAreDraftAndNoAuction() {
        Product product = new Product();
        assertEquals(ProductStatus.DRAFT, product.getStatus());
        assertFalse(product.getAuctionEnabled());
        assertNotNull(product.getImages());
        assertTrue(product.getImages().isEmpty());
        assertNotNull(product.getSkus());
        assertTrue(product.getSkus().isEmpty());
    }

    @Test
    void settersAndGettersRoundTrip() {
        Product product = new Product();
        UUID id = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Category category = new Category();

        product.setId(id);
        product.setSellerId(sellerId);
        product.setCategory(category);
        product.setName("Laptop");
        product.setDescription("Desc");
        product.setStatus(ProductStatus.ACTIVE);
        product.setAuctionEnabled(true);

        assertEquals(id, product.getId());
        assertEquals(sellerId, product.getSellerId());
        assertEquals(category, product.getCategory());
        assertEquals("Laptop", product.getName());
        assertEquals("Desc", product.getDescription());
        assertEquals(ProductStatus.ACTIVE, product.getStatus());
        assertTrue(product.getAuctionEnabled());
    }

    @Test
    void statusCanBeInactive() {
        Product product = new Product();
        product.setStatus(ProductStatus.INACTIVE);
        assertEquals(ProductStatus.INACTIVE, product.getStatus());
    }

    @Test
    void imagesAndSkusAreMutable() {
        Product product = new Product();
        ProductImage image = new ProductImage();
        Sku sku = new Sku();

        product.getImages().add(image);
        product.getSkus().add(sku);
        assertEquals(1, product.getImages().size());
        assertEquals(1, product.getSkus().size());

        product.setImages(List.of(image));
        product.setSkus(List.of(sku));
        assertEquals(1, product.getImages().size());
        assertEquals(1, product.getSkus().size());
    }

    @Test
    void createdAtIsSettable() {
        Product product = new Product();
        assertNull(product.getCreatedAt());
        java.time.Instant now = java.time.Instant.now();
        product.setCreatedAt(now);
        assertEquals(now, product.getCreatedAt());
    }

    @Test
    void validProductHasNoViolations() {
        assertTrue(validator.validate(newValidProduct()).isEmpty());
    }

    @Test
    void blankNameIsRejected() {
        Product product = newValidProduct();
        product.setName(" ");
        assertFalse(validator.validate(product).isEmpty());
    }

    @Test
    void nullNameIsRejected() {
        Product product = newValidProduct();
        product.setName(null);
        assertFalse(validator.validate(product).isEmpty());
    }

    @Test
    void nameLongerThan255IsRejected() {
        Product product = newValidProduct();
        product.setName("x".repeat(256));
        assertFalse(validator.validate(product).isEmpty());
    }

    @Test
    void moreThanFiveImagesIsRejected() {
        Product product = newValidProduct();
        for (int i = 0; i < 6; i++) {
            ProductImage image = new ProductImage();
            image.setId(UUID.randomUUID());
            image.setUrl("https://cdn.example.com/img" + i + ".jpg");
            image.setSortOrder(i);
            product.getImages().add(image);
        }
        assertFalse(validator.validate(product).isEmpty());
    }

    @Test
    void fiveImagesAreAccepted() {
        Product product = newValidProduct();
        for (int i = 0; i < 5; i++) {
            ProductImage image = new ProductImage();
            image.setId(UUID.randomUUID());
            image.setUrl("https://cdn.example.com/img" + i + ".jpg");
            image.setSortOrder(i);
            product.getImages().add(image);
        }
        assertTrue(validator.validate(product).isEmpty());
    }
}
