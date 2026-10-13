package com.nexus.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service mô phỏng việc gửi email thông báo đặt lại mật khẩu theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    /**
     * Gửi email chứa token đặt lại mật khẩu đến người dùng.
     *
     * @param toEmail    Địa chỉ email người nhận
     * @param resetToken Mã token đặt lại mật khẩu
     */
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        log.info("[EMAIL MOCK] Đã gửi email đặt lại mật khẩu tới: {}", toEmail);
        log.info("[EMAIL MOCK] Mã reset token: {}", resetToken);
        log.info("[EMAIL MOCK] Đường dẫn reset: https://nexus.fpt.edu.vn/reset-password?token={}", resetToken);
    }
}
