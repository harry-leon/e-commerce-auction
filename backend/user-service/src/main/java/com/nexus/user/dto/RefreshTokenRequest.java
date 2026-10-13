package com.nexus.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DTO nhận yêu cầu làm mới access token bằng refresh token.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token không được để trống")
    private String refreshToken;

    /**
     * Khởi tạo đối tượng RefreshTokenRequest mặc định.
     */
    public RefreshTokenRequest() {
    }

    /**
     * Khởi tạo đối tượng RefreshTokenRequest với refresh token.
     *
     * @param refreshToken Chuỗi refresh token
     */
    public RefreshTokenRequest(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
