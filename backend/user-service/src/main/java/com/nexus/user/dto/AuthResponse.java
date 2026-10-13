package com.nexus.user.dto;

/**
 * DTO trả về kết quả sau khi đăng nhập hoặc làm mới token thành công.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private long expiresIn;
    private UserResponse user;

    /**
     * Khởi tạo đối tượng AuthResponse mặc định.
     */
    public AuthResponse() {
    }

    /**
     * Khởi tạo đối tượng AuthResponse với các tham số chính.
     *
     * @param accessToken  Token truy cập ngắn hạn
     * @param refreshToken Token làm mới dài hạn
     * @param expiresIn    Thời gian sống của accessToken (tính theo giây)
     * @param user         Thông tin người dùng đăng nhập
     */
    public AuthResponse(String accessToken, String refreshToken, long expiresIn, UserResponse user) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = "Bearer";
        this.expiresIn = expiresIn;
        this.user = user;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserResponse getUser() {
        return user;
    }

    public void setUser(UserResponse user) {
        this.user = user;
    }
}
