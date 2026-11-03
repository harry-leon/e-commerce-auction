package com.nexus.catalog.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tính đúng đắn và validation của lớp CatalogProperties.
 * Đảm bảo các giá trị mặc định khớp quy định và chặn cấu hình vi phạm.
 */
class CatalogPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void defaultValues_shouldBeValid() {
        CatalogProperties properties = new CatalogProperties();

        assertEquals(5, properties.getProduct().getMaxImages());
        assertEquals(20, properties.getPagination().getDefaultPageSize());
        assertEquals(100, properties.getPagination().getMaxPageSize());

        var violations = validator.validate(properties);
        assertTrue(violations.isEmpty(), "Cấu hình mặc định phải hợp lệ không có lỗi validation");
    }

    @Test
    void invalidMaxImages_shouldFailValidation() {
        CatalogProperties properties = new CatalogProperties();
        properties.getProduct().setMaxImages(0);

        var violations = validator.validate(properties);
        assertFalse(violations.isEmpty(), "maxImages = 0 phải bị chặn bởi @Min(1)");
    }
}
