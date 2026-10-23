package com.nexus.fulfillment.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tính đúng đắn và validation của lớp FulfillmentProperties.
 * Đảm bảo các giá trị mặc định hợp lệ và chặn cấu hình sai quy chuẩn.
 */
class FulfillmentPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void defaultValues_shouldBeValid() {
        FulfillmentProperties properties = new FulfillmentProperties();

        assertEquals(3, properties.getShipping().getDefaultDeliveryDays());
        assertEquals(3, properties.getShipping().getMaxRetryAttempts());
        assertEquals(7, properties.getShipping().getAutoConfirmDays());

        var violations = validator.validate(properties);
        assertTrue(violations.isEmpty(), "Cấu hình mặc định của fulfillment-service phải hợp lệ");
    }

    @Test
    void invalidDeliveryDays_shouldFailValidation() {
        FulfillmentProperties properties = new FulfillmentProperties();
        properties.getShipping().setDefaultDeliveryDays(0);

        var violations = validator.validate(properties);
        assertFalse(violations.isEmpty(), "Số ngày giao hàng tối thiểu = 0 phải bị chặn bởi @Min(1)");
    }
}
