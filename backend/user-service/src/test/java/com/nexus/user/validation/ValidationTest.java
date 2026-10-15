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
        assertTrue(phoneValidator.isValid("0912345678", null));
    }

    @Test
    void testPhoneValidator_elevenDigitsIsValid() {
        assertTrue(phoneValidator.isValid("09123456789", null));
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
