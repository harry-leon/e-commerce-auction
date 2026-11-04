package com.nexus.user.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

/** User Service phải chặn CCCD sai ở cả API tạo và cập nhật, kể cả khi client bỏ qua Gateway. */
class IdentityNumberValidationTest {
    @Test
    void validatesCreateAndUpdateIdentityNumbersWithoutChangingStoredData() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var create = new CreateUserProfileRequest();
            var update = new UpdateUserRequest();
            for (String number : new String[]{null, "", " ", "079xxxxxxxxx", "07920300123",
                    "0792030012345", "07920300123a", " 079203001234", "079203001234 ", "０７９２０３００１２３４"}) {
                create.setIdentifyNumber(number);
                update.setIdentifyNumber(number);
                assertFalse(validator.validateProperty(create, "identifyNumber").isEmpty());
                assertFalse(validator.validateProperty(update, "identifyNumber").isEmpty());
            }
            // CCCD là chuỗi để giữ số 0 đầu; kiểm tra định dạng không khẳng định đây là danh tính thật.
            create.setIdentifyNumber("079203001234");
            update.setIdentifyNumber("079203001234");
            assertTrue(validator.validateProperty(create, "identifyNumber").isEmpty());
            assertTrue(validator.validateProperty(update, "identifyNumber").isEmpty());
        }
    }
}
