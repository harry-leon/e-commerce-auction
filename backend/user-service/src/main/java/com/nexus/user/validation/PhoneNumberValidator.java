package com.nexus.user.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Triển khai kiểm tra số điện thoại Việt Nam hợp lệ cho annotation {@link ValidPhoneNumber}.
 * Cho phép giá trị null hoặc rỗng (kết hợp với @NotBlank/@NotNull nếu bắt buộc).
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
public class PhoneNumberValidator implements ConstraintValidator<ValidPhoneNumber, String> {

    /**
     * Chỉ nhận dạng nội địa 10 chữ số ASCII, bắt đầu 03/05/07/08/09; rỗng vẫn là trường tùy chọn.
     * Không tự đổi +84, trim hoặc nhận số cố định; đây là kiểm tra định dạng, không xác minh thuê bao/OTP.
     */
    private static final String PHONE_PATTERN = "(?:|0[35789][0-9]{8})";

    /**
     * Kiểm tra giá trị số điện thoại có hợp lệ hay không.
     *
     * @param value   Giá trị số điện thoại cần kiểm tra
     * @param context Ngữ cảnh validation
     * @return true nếu hợp lệ, false nếu không
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true; // null được coi là hợp lệ (dùng @NotNull nếu bắt buộc)
        }
        return value.matches(PHONE_PATTERN);
    }
}
