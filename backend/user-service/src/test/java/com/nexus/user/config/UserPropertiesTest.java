package com.nexus.user.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tính đúng đắn và validation của lớp UserProperties.
 * Đảm bảo các giá trị mặc định hợp lệ và chặn cấu hình sai quy chuẩn.
 */
class UserPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void defaultValues_shouldBeValid() {
        UserProperties properties = new UserProperties();

        assertEquals(60L, properties.getSecurity().getAccessTokenExpiryMinutes());
        assertEquals(7, properties.getSecurity().getRefreshTokenExpiryDays());
        assertEquals(5, properties.getSecurity().getMaxLoginAttempts());
        assertEquals(15, properties.getSecurity().getResetPasswordExpiryMinutes());
        assertEquals(24, properties.getVerification().getEmailVerificationExpiryHours());

        var violations = validator.validate(properties);
        assertTrue(violations.isEmpty(), "Cấu hình mặc định của user-service phải hợp lệ");
    }

    @Test
    void invalidAccessTokenExpiry_shouldFailValidation() {
        UserProperties properties = new UserProperties();
        properties.getSecurity().setAccessTokenExpiryMinutes(0L);

        var violations = validator.validate(properties);
        assertFalse(violations.isEmpty(), "Thời hạn access token = 0 phải bị chặn bởi @Min(1)");
    }
}
