package com.nexus.auction.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tính đúng đắn và validation của lớp AuctionProperties.
 * Đảm bảo các giá trị mặc định hợp lệ và chặn cấu hình sai quy chuẩn.
 */
class AuctionPropertiesTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void defaultValues_shouldBeValid() {
        AuctionProperties properties = new AuctionProperties();

        assertEquals(1000L, properties.getBidding().getMinIncrement());
        assertEquals(120, properties.getBidding().getAntiSnipingSeconds());
        assertEquals(20, properties.getRules().getMaxActiveAuctionsPerSeller());

        var violations = validator.validate(properties);
        assertTrue(violations.isEmpty(), "Cấu hình mặc định của auction-service phải hợp lệ");
    }

    @Test
    void invalidMinIncrement_shouldFailValidation() {
        AuctionProperties properties = new AuctionProperties();
        properties.getBidding().setMinIncrement(0L);

        var violations = validator.validate(properties);
        assertFalse(violations.isEmpty(), "Bước giá tối thiểu = 0 phải bị chặn bởi @Min(1)");
    }
}
