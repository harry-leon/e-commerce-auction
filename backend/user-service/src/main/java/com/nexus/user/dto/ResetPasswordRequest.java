package com.nexus.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO nhận yêu cầu đặt lại mật khẩu mới thông qua token xác nhận.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class ResetPasswordRequest {

    @NotBlank(message = "Token không được để trống")
    private String token;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @Size(min = 6, max = 50, message = "Mật khẩu mới phải từ 6 đến 50 ký tự")
    private String newPassword;

    /**
     * Khởi tạo đối tượng ResetPasswordRequest mặc định.
     */
    public ResetPasswordRequest() {
    }

    /**
     * Khởi tạo đối tượng ResetPasswordRequest với token và mật khẩu mới.
     *
     * @param token       Mã token đặt lại mật khẩu đã nhận qua email
     * @param newPassword Mật khẩu mới cần cập nhật
     */
    public ResetPasswordRequest(String token, String newPassword) {
        this.token = token;
        this.newPassword = newPassword;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
