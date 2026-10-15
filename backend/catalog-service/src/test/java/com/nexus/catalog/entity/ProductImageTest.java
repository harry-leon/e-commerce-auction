package com.nexus.catalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductImageTest {

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

    private ProductImage newValidImage() {
        ProductImage image = new ProductImage();
        image.setId(UUID.randomUUID());
        image.setProduct(new Product());
        image.setUrl("https://cdn.example.com/p/1.jpg");
        return image;
    }

    @Test
    void defaultSortOrderIsZero() {
        ProductImage image = new ProductImage();
        assertEquals(0, image.getSortOrder());
    }

    @Test
    void settersAndGettersRoundTrip() {
        ProductImage image = new ProductImage();
        UUID id = UUID.randomUUID();
        Product product = new Product();

        image.setId(id);
        image.setProduct(product);
        image.setUrl("https://cdn.example.com/p/2.jpg");
        image.setSortOrder(3);

        assertEquals(id, image.getId());
        assertEquals(product, image.getProduct());
        assertEquals("https://cdn.example.com/p/2.jpg", image.getUrl());
        assertEquals(3, image.getSortOrder());
    }

    @Test
    void sortOrderIsNullUntilSet() {
        ProductImage image = new ProductImage();
        assertNull(image.getProduct());
        assertNull(image.getUrl());
    }

    @Test
    void validImageHasNoViolations() {
        assertTrue(validator.validate(newValidImage()).isEmpty());
    }

    @Test
    void blankUrlIsRejected() {
        ProductImage image = newValidImage();
        image.setUrl("");
        assertFalse(validator.validate(image).isEmpty());
    }

    @Test
    void nullUrlIsRejected() {
        ProductImage image = newValidImage();
        image.setUrl(null);
        assertFalse(validator.validate(image).isEmpty());
    }

    @Test
    void urlLongerThan500IsRejected() {
        ProductImage image = newValidImage();
        image.setUrl("https://cdn.example.com/" + "x".repeat(500));
        assertFalse(validator.validate(image).isEmpty());
    }

    @Test
    void negativeSortOrderIsRejected() {
        ProductImage image = newValidImage();
        image.setSortOrder(-1);
        assertFalse(validator.validate(image).isEmpty());
    }
}
