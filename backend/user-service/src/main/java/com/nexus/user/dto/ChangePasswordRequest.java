package com.nexus.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO nhận yêu cầu đổi mật khẩu cho người dùng đang đăng nhập.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class ChangePasswordRequest {

    @NotBlank(message = "Mật khẩu hiện tại không được để trống")
    private String currentPassword;

    @NotBlank(message = "Mật khẩu mới không được để trống")
    @Size(min = 6, max = 100, message = "Mật khẩu mới phải từ 6 đến 100 ký tự")
    private String newPassword;

    /**
     * Khởi tạo đối tượng ChangePasswordRequest mặc định.
     */
    public ChangePasswordRequest() {
    }

    /**
     * Khởi tạo đối tượng ChangePasswordRequest với mật khẩu hiện tại và mới.
     *
     * @param currentPassword Mật khẩu hiện tại của tài khoản
     * @param newPassword     Mật khẩu mới muốn thay đổi
     */
    public ChangePasswordRequest(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
