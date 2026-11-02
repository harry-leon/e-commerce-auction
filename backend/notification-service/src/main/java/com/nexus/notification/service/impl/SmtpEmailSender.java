package com.nexus.notification.service.impl;

import com.nexus.notification.exception.EmailDeliveryException;
import com.nexus.notification.service.EmailSender;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Cài đặt EmailSender qua máy chủ SMTP / Mailpit trên cổng 1025 (SRS §3.2.3).
 *
 * Chức năng:
 * - Soạn thảo MimeMessage đa phần (Multipart) hỗ trợ cả HTML UTF-8 và Plain Text dự phòng.
 * - Gửi thư qua JavaMailSender tới hạ tầng Mailpit local hoặc SMTP gateway.
 *
 * Xử lý lỗi:
 * - Chuyển đổi các ngoại lệ MailException, MessagingException thành EmailDeliveryException
 *   để tầng nghiệp vụ nhận biết và kích hoạt logic thử lại (Retry).
 */
@Service
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;

    @Autowired
    public SmtpEmailSender(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public void sendEmail(String from, String to, String subject, String bodyHtml, String bodyText)
            throws EmailDeliveryException {
        if (mailSender == null) {
            log.warn("JavaMailSender không được kích hoạt trên hệ thống; bỏ qua tiến trình SMTP thực tế");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            // Gửi cả plain text và html để tương thích với mọi email client
            helper.setText(bodyText != null ? bodyText : "", bodyHtml != null ? bodyHtml : "");

            log.info("Bắt đầu gửi email SMTP tới: {} với tiêu đề: '{}'", to, subject);
            mailSender.send(message);
            log.info("Gửi email SMTP thành công tới: {}", to);
        } catch (MailException | MessagingException ex) {
            log.error("Lỗi mạng khi kết nối máy chủ SMTP tới {}: {}", to, ex.getMessage());
            throw new EmailDeliveryException("Lỗi kết nối máy chủ gửi thư SMTP: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            log.error("Lỗi không xác định khi soạn gửi email tới {}: {}", to, ex.getMessage(), ex);
            throw new EmailDeliveryException("Lỗi nội bộ khi gửi email: " + ex.getMessage(), ex);
        }
    }
}
