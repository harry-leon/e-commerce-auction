package com.nexus.user.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Lớp cấu hình thuộc tính nghiệp vụ dành riêng cho user-service.
 *
 * Chức năng:
 * - Ánh xạ type-safe các tham số cấu hình từ prefix 'app.user' nạp từ Spring Cloud Config Server.
 * - Hỗ trợ cập nhật tham số tại runtime thông qua @RefreshScope và actuator refresh endpoint.
 *
 * Quy tắc và lý do:
 * - Tập trung hóa thời hạn token, số lần thử đăng nhập và thời hạn xác thực tài khoản.
 * - Không phụ thuộc Lombok (sử dụng getter/setter thuần theo chuẩn thiết kế của user-service).
 * - Có sẵn giá trị mặc định để ứng dụng và các unit test vẫn chạy độc lập an toàn khi Config Server chưa bật.
 * - Dùng Bean Validation (@Validated, @NotNull, @Min) ngăn chặn cấu hình sai giá trị âm hoặc không hợp lệ.
 */
@Component
@ConfigurationProperties(prefix = "app.user")
@RefreshScope
@Validated
public class UserProperties {

    @Valid
    @NotNull
    private Security security = new Security();

    @Valid
    @NotNull
    private Verification verification = new Verification();

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Verification getVerification() {
        return verification;
    }

    public void setVerification(Verification verification) {
        this.verification = verification;
    }

    public static class Security {
        @NotNull
        @Min(value = 1, message = "Thời hạn access token tối thiểu phải là 1 phút")
        private Long accessTokenExpiryMinutes = 60L;

        @NotNull
        @Min(value = 1, message = "Thời hạn refresh token tối thiểu phải là 1 ngày")
        private Integer refreshTokenExpiryDays = 7;

        @NotNull
        @Min(value = 1, message = "Số lần đăng nhập sai tối thiểu phải là 1")
        private Integer maxLoginAttempts = 5;

        @NotNull
        @Min(value = 1, message = "Thời hạn reset password tối thiểu phải là 1 phút")
        private Integer resetPasswordExpiryMinutes = 15;

        public Long getAccessTokenExpiryMinutes() {
            return accessTokenExpiryMinutes;
        }

        public void setAccessTokenExpiryMinutes(Long accessTokenExpiryMinutes) {
            this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        }

        public Integer getRefreshTokenExpiryDays() {
            return refreshTokenExpiryDays;
        }

        public void setRefreshTokenExpiryDays(Integer refreshTokenExpiryDays) {
            this.refreshTokenExpiryDays = refreshTokenExpiryDays;
        }

        public Integer getMaxLoginAttempts() {
            return maxLoginAttempts;
        }

        public void setMaxLoginAttempts(Integer maxLoginAttempts) {
            this.maxLoginAttempts = maxLoginAttempts;
        }

        public Integer getResetPasswordExpiryMinutes() {
            return resetPasswordExpiryMinutes;
        }

        public void setResetPasswordExpiryMinutes(Integer resetPasswordExpiryMinutes) {
            this.resetPasswordExpiryMinutes = resetPasswordExpiryMinutes;
        }
    }

    public static class Verification {
        @NotNull
        @Min(value = 1, message = "Thời hạn xác thực email tối thiểu phải là 1 giờ")
        private Integer emailVerificationExpiryHours = 24;

        public Integer getEmailVerificationExpiryHours() {
            return emailVerificationExpiryHours;
        }

        public void setEmailVerificationExpiryHours(Integer emailVerificationExpiryHours) {
            this.emailVerificationExpiryHours = emailVerificationExpiryHours;
        }
    }
}
