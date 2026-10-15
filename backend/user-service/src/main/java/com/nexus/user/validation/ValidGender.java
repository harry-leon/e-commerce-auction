package com.nexus.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation kiểm tra giá trị giới tính hợp lệ: MALE, FEMALE, OTHER hoặc null/rỗng.
 * Dùng chung cho tất cả service cần validate giới tính theo SRS §3.1.1.
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
@Documented
@Constraint(validatedBy = GenderValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidGender {

    /**
     * Thông báo lỗi mặc định khi không hợp lệ.
     *
     * @return Chuỗi thông báo lỗi
     */
    String message() default "Giới tính phải là MALE, FEMALE hoặc OTHER";

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
