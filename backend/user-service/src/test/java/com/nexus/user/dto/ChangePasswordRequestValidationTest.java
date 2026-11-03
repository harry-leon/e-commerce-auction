package com.nexus.user.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** Kiểm tra policy đổi mật khẩu độc lập controller: không để regex Gateway/Keycloak và User bị lệch. */
class ChangePasswordRequestValidationTest {
    /** Chấp nhận chữ hoa Unicode và giới hạn 100; từ chối thiếu từng loại ký tự, vượt độ dài hoặc chỉ có khoảng trắng. */
    @Test
    void validatesPolicyAndLengthBoundariesWithoutChangingCredentials() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String password : new String[]{"Nexus12@", "Đabcdef1!", "A1!" + "a".repeat(97)}) {
                assertTrue(validator.validate(new ChangePasswordRequest("old-password", password)).isEmpty());
            }
            for (String password : new String[]{null, "", "123123@", "abcdefg1@", "ABCDEFG!", "Abcdef12 ", "A1!" + "a".repeat(98)}) {
                assertFalse(validator.validate(new ChangePasswordRequest("old-password", password)).isEmpty());
            }
            // Mật khẩu trong ảnh thiếu đúng độ dài và chữ hoa; BE phải trả đủ cả hai thông điệp cho FE.
            assertEquals(2, validator.validate(new ChangePasswordRequest("old-password", "123123@")).size());
        }
    }
}
