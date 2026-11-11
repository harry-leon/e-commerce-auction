package com.nexus.catalog.entity;

import com.nexus.catalog.enums.SkuStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SkuTest {

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

    private Sku newValidSku() {
        Sku sku = new Sku();
        sku.setId(UUID.randomUUID());
        sku.setProduct(new Product());
        sku.setSkuCode("SKU-001");
        sku.setOriginalPrice(new BigDecimal("1990000"));
        return sku;
    }

    @Test
    void defaultStatusIsActive() {
        Sku sku = new Sku();
        assertEquals(SkuStatus.ACTIVE, sku.getStatus());
    }

    @Test
    void settersAndGettersRoundTrip() {
        Sku sku = new Sku();
        UUID id = UUID.randomUUID();
        Product product = new Product();
        BigDecimal original = new BigDecimal("200.00");

        sku.setId(id);
        sku.setProduct(product);
        sku.setSkuCode("SKU-777");
        sku.setAttributes("{\"color\":\"red\"}");
        sku.setOriginalPrice(original);
        sku.setStatus(SkuStatus.INACTIVE);

        assertEquals(id, sku.getId());
        assertEquals(product, sku.getProduct());
        assertEquals("SKU-777", sku.getSkuCode());
        assertEquals("{\"color\":\"red\"}", sku.getAttributes());
        assertEquals(original, sku.getOriginalPrice());
        assertEquals(SkuStatus.INACTIVE, sku.getStatus());
    }

    @Test
    void originalPriceIsNullByDefault() {
        Sku sku = new Sku();
        assertNull(sku.getOriginalPrice());
        assertNull(sku.getAttributes());
    }

    @Test
    void validSkuHasNoViolations() {
        assertTrue(validator.validate(newValidSku()).isEmpty());
    }

    @Test
    void blankSkuCodeIsRejected() {
        Sku sku = newValidSku();
        sku.setSkuCode("");
        assertFalse(validator.validate(sku).isEmpty());
    }

    @Test
    void nullOriginalPriceIsRejected() {
        Sku sku = newValidSku();
        sku.setOriginalPrice(null);
        assertFalse(validator.validate(sku).isEmpty());
    }

    @Test
    void negativeOriginalPriceIsRejected() {
        Sku sku = newValidSku();
        sku.setOriginalPrice(new BigDecimal("-1"));
        assertFalse(validator.validate(sku).isEmpty());
    }

    @Test
    void zeroOriginalPriceIsAccepted() {
        Sku sku = newValidSku();
        sku.setOriginalPrice(BigDecimal.ZERO);
        assertTrue(validator.validate(sku).isEmpty());
    }

    @Test
    void skuCodeLongerThan100IsRejected() {
        Sku sku = newValidSku();
        sku.setSkuCode("x".repeat(101));
        assertFalse(validator.validate(sku).isEmpty());
    }
}
