package com.nexus.catalog.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.catalog.model.entity.Product;
import com.nexus.catalog.model.entity.ProductReview;
import com.nexus.catalog.model.entity.Sku;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProductReviewTest {

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

    private ProductReview newValidReview() {
        ProductReview review = new ProductReview();
        review.setId(UUID.randomUUID());
        review.setProduct(new Product());
        review.setSku(new Sku());
        review.setUserId(UUID.randomUUID());
        review.setOrderId(UUID.randomUUID());
        review.setScore(5);
        review.setComment("Great product!");
        review.setMediaUrls("[\"https://cdn.example.com/review1.jpg\"]");
        review.setIsHidden(false);
        review.setCreatedAt(Instant.now());
        return review;
    }

    @Test
    void validReviewPasses() {
        ProductReview review = newValidReview();
        assertTrue(validator.validate(review).isEmpty());
    }

    @Test
    void scoreUnder1Fails() {
        ProductReview review = newValidReview();
        review.setScore(0);
        assertFalse(validator.validate(review).isEmpty());
    }

    @Test
    void scoreOver5Fails() {
        ProductReview review = newValidReview();
        review.setScore(6);
        assertFalse(validator.validate(review).isEmpty());
    }

    @Test
    void nullScoreFails() {
        ProductReview review = newValidReview();
        review.setScore(null);
        assertFalse(validator.validate(review).isEmpty());
    }

    @Test
    void nullUserIdFails() {
        ProductReview review = newValidReview();
        review.setUserId(null);
        assertFalse(validator.validate(review).isEmpty());
    }

    @Test
    void nullOrderIdFails() {
        ProductReview review = newValidReview();
        review.setOrderId(null);
        assertFalse(validator.validate(review).isEmpty());
    }

    @Test
    void gettersAndSettersWork() {
        ProductReview review = new ProductReview();
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Product product = new Product();
        Sku sku = new Sku();
        Instant now = Instant.now();

        review.setId(id);
        review.setProduct(product);
        review.setSku(sku);
        review.setUserId(userId);
        review.setOrderId(orderId);
        review.setScore(4);
        review.setComment("Good");
        review.setMediaUrls("[]");
        review.setSellerReply("Thank you!");
        review.setSellerRepliedAt(now);
        review.setIsHidden(true);
        review.setCreatedAt(now);

        assertEquals(id, review.getId());
        assertEquals(product, review.getProduct());
        assertEquals(sku, review.getSku());
        assertEquals(userId, review.getUserId());
        assertEquals(orderId, review.getOrderId());
        assertEquals(4, review.getScore());
        assertEquals("Good", review.getComment());
        assertEquals("[]", review.getMediaUrls());
        assertEquals("Thank you!", review.getSellerReply());
        assertEquals(now, review.getSellerRepliedAt());
        assertTrue(review.getIsHidden());
        assertEquals(now, review.getCreatedAt());
    }
}
