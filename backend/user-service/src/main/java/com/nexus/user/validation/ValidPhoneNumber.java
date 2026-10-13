package com.nexus.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation kiểm tra số điện thoại Việt Nam hợp lệ (10–11 chữ số, có thể rỗng).
 * Dùng chung cho tất cả service cần validate số điện thoại.
 *
 * <p>Số điện thoại hợp lệ:
 * <ul>
 *   <li>Rỗng hoặc null (không bắt buộc)</li>
 *   <li>Chứa đúng 10 hoặc 11 chữ số</li>
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
    String message() default "Số điện thoại không hợp lệ, phải có 10–11 chữ số";

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
