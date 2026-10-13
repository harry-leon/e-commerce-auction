package com.nexus.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * Triển khai kiểm tra giá trị giới tính hợp lệ cho annotation {@link ValidGender}.
 * Cho phép null hoặc rỗng; nếu có giá trị phải là một trong: MALE, FEMALE, OTHER.
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
public class GenderValidator implements ConstraintValidator<ValidGender, String> {

    private static final Set<String> ALLOWED_VALUES = Set.of("MALE", "FEMALE", "OTHER");

    /**
     * Kiểm tra giá trị giới tính có hợp lệ không.
     *
     * @param value   Giá trị giới tính cần kiểm tra
     * @param context Ngữ cảnh validation
     * @return true nếu hợp lệ, false nếu không
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // Cho phép null/rỗng (không bắt buộc)
        }
        return ALLOWED_VALUES.contains(value.toUpperCase());
    }
}
