package com.nexus.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * DTO nhận dữ liệu yêu cầu đăng nhập tài khoản theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class LoginRequest {

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không đúng định dạng")
    private String email;

    @NotBlank(message = "Mật khẩu không được để trống")
    private String password;

    /**
     * Khởi tạo đối tượng LoginRequest mặc định.
     */
    public LoginRequest() {
    }

    /**
     * Khởi tạo đối tượng LoginRequest với email và password.
     *
     * @param email    Địa chỉ email đăng nhập
     * @param password Mật khẩu
     */
    public LoginRequest(String email, String password) {
        this.email = email;
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
