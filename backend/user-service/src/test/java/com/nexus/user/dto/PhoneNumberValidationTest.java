package com.nexus.user.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** Bảo vệ việc tạo/cập nhật dùng cùng annotation di động Việt Nam; phone vẫn tùy chọn ở BE. */
class PhoneNumberValidationTest {
    @Test
    void usesDomesticMobileValidationOnBothProfileRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create = new CreateUserProfileRequest();
            var update = new UpdateUserRequest();
            for (String phone : new String[]{null, "", "0321234567", "0561234567", "0701234567", "0812345678", "0912345678"}) {
                create.setPhone(phone);
                update.setPhone(phone);
                assertTrue(validator.validateProperty(create, "phone").isEmpty());
                assertTrue(validator.validateProperty(update, "phone").isEmpty());
            }
            // API trực tiếp không được lưu số 11 chữ số, số cố định hoặc dạng quốc tế mà Gateway đã chặn.
            for (String phone : new String[]{"09123456789", "0212345678", "+84912345678", "091234567a", " "}) {
                create.setPhone(phone);
                update.setPhone(phone);
                assertFalse(validator.validateProperty(create, "phone").isEmpty());
                assertFalse(validator.validateProperty(update, "phone").isEmpty());
            }
        }
    }
}
