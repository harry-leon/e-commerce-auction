package com.nexus.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO nhận yêu cầu quên mật khẩu qua email theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class ForgotPasswordRequest {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    /**
     * Khởi tạo đối tượng ForgotPasswordRequest mặc định.
     */
    public ForgotPasswordRequest() {
    }

    /**
     * Khởi tạo đối tượng ForgotPasswordRequest với email.
     *
     * @param email Địa chỉ email của tài khoản cần đặt lại mật khẩu
     */
    public ForgotPasswordRequest(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
