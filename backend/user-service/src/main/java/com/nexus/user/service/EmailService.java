package com.nexus.user.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Gửi email đặt lại mật khẩu qua SMTP bằng JavaMailSender, thay cho cách ghi token vào log.
 * Local Compose chuyển thư tới Mailpit; host/cổng/tài khoản SMTP do cấu hình môi trường quyết định.
 */
@Service
public class EmailService {
    private final JavaMailSender sender;
    private final String from;
    private final String frontendUrl;

    public EmailService(JavaMailSender sender,
            @Value("${app.mail.from:noreply@nexus.local}") String from,
            @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.sender = sender;
        this.from = from;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Nhận email và token do AuthService tạo, dựng link frontend rồi gửi thư đồng bộ.
     * Không log token; lỗi SMTP được truyền lên caller, không giả báo gửi thư thành công.
     */
    public void sendPasswordResetEmail(String toEmail, String resetToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Nexus - Reset your password");
        // Mã hóa token trong query string để các ký tự đặc biệt không làm sai liên kết nhận qua email.
        String link = frontendUrl.replaceAll("/$", "") + "/forgot-password?token="
                + URLEncoder.encode(resetToken, StandardCharsets.UTF_8);
        message.setText("Use this link to reset your password:\n" + link
                + "\nIf you did not request a password reset, ignore this message.");
        sender.send(message);
    }
}
