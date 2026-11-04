package com.nexus.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation dùng chung cho DTO tạo/cập nhật hồ sơ trong User Service.
 * Kiểm tra định dạng di động nội địa 10 chữ số, không xác minh thuê bao hoặc OTP.
 *
 * <p>Số điện thoại hợp lệ:
 * <ul>
 *   <li>Rỗng hoặc null (không bắt buộc)</li>
 *   <li>Đúng 10 chữ số ASCII, bắt đầu 03/05/07/08/09</li>
 *   <li>Không chứa ký tự đặc biệt, khoảng trắng hay dấu chấm</li>
 * </ul>
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
@Documented
@Constraint(validatedBy = PhoneNumberValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPhoneNumber {

    /**
     * Thông báo lỗi mặc định khi không hợp lệ.
     *
     * @return Chuỗi thông báo lỗi
     */
    String message() default "Số điện thoại phải có đúng 10 chữ số và bắt đầu bằng 03, 05, 07, 08 hoặc 09";

    /**
     * Nhóm validation.
     *
     * @return Mảng nhóm
     */
    Class<?>[] groups() default {};

    /**
     * Payload metadata.
     *
     * @return Mảng payload
     */
    Class<? extends Payload>[] payload() default {};
}
