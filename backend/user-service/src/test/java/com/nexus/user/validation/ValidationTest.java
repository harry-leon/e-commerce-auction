package com.nexus.user.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử đơn vị cho PhoneNumberValidator và GenderValidator (KH-S1-2).
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
class ValidationTest {

    private PhoneNumberValidator phoneValidator;
    private GenderValidator genderValidator;

    @BeforeEach
    void setUp() {
        phoneValidator = new PhoneNumberValidator();
        genderValidator = new GenderValidator();
    }

    // ---------- PhoneNumberValidator ----------

    @Test
    void testPhoneValidator_nullIsValid() {
        assertTrue(phoneValidator.isValid(null, null));
    }

    @Test
    void testPhoneValidator_emptyIsValid() {
        assertTrue(phoneValidator.isValid("", null));
    }

    @Test
    void testPhoneValidator_tenDigitsIsValid() {
        // Chấp nhận các nhóm đầu số di động nội địa, không giới hạn theo nhà mạng của người dùng.
        for (String phone : new String[]{"0321234567", "0561234567", "0701234567", "0812345678", "0912345678"}) {
            assertTrue(phoneValidator.isValid(phone, null));
        }
    }

    @Test
    void testPhoneValidator_elevenDigitsIsInvalid() {
        // Chặn số 11 chữ số dù có đầu 09 hợp lệ để bảo vệ quy tắc độ dài mới.
        assertFalse(phoneValidator.isValid("09123456789", null));
    }

    /** Chặn đầu số cố định/sai nhóm, dạng quốc tế, dấu phân cách và chữ số không phải ASCII. */
    @Test
    void rejectsNonDomesticMobileFormats() {
        for (String phone : new String[]{"0212345678", "0412345678", "0612345678", "0123456789",
                "1234567890", "+84912345678", "84912345678", " 0912345678", "0912345678 ",
                "091-234-5678", "０９１２３４５６７８"}) {
            assertFalse(phoneValidator.isValid(phone, null));
        }
    }

    @Test
    void testPhoneValidator_nineDigitsIsInvalid() {
        assertFalse(phoneValidator.isValid("091234567", null));
    }

    @Test
    void testPhoneValidator_containsLettersIsInvalid() {
        assertFalse(phoneValidator.isValid("091234567a", null));
    }

    @Test
    void testPhoneValidator_containsSpaceIsInvalid() {
        assertFalse(phoneValidator.isValid("091 234 5678", null));
    }

    // ---------- GenderValidator ----------

    @Test
    void testGenderValidator_nullIsValid() {
        assertTrue(genderValidator.isValid(null, null));
    }

    @Test
    void testGenderValidator_emptyIsValid() {
        assertTrue(genderValidator.isValid("", null));
    }

    @Test
    void testGenderValidator_maleIsValid() {
        assertTrue(genderValidator.isValid("MALE", null));
    }

    @Test
    void testGenderValidator_femaleIsValid() {
        assertTrue(genderValidator.isValid("FEMALE", null));
    }

    @Test
    void testGenderValidator_otherIsValid() {
        assertTrue(genderValidator.isValid("OTHER", null));
    }

    @Test
    void testGenderValidator_lowercaseIsValid() {
        assertTrue(genderValidator.isValid("male", null));
    }

    @Test
    void testGenderValidator_invalidValueIsInvalid() {
        assertFalse(genderValidator.isValid("UNKNOWN", null));
    }

    @Test
    void testGenderValidator_randomStringIsInvalid() {
        assertFalse(genderValidator.isValid("X", null));
    }
}
