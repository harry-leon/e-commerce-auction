package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.notification.exception.EmailDeliveryException;
import com.nexus.notification.service.impl.SmtpEmailSender;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Kiểm thử adapter gửi email SMTP thực tế (SmtpEmailSender).
 *
 * Tình huống kiểm thử:
 * 1. JavaMailSender null: Bỏ qua êm thuận không ném lỗi.
 * 2. Gửi thư thành công qua JavaMailSender: Tạo MimeMessage và gọi send.
 * 3. Bắt lỗi MailException và bọc thành EmailDeliveryException để kích hoạt cơ chế retry.
 */
@ExtendWith(MockitoExtension.class)
class SmtpEmailSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpEmailSender smtpEmailSender;

    @BeforeEach
    void setUp() {
        smtpEmailSender = new SmtpEmailSender(mailSender);
    }

    @Test
    @DisplayName("Khi JavaMailSender không tồn tại (null): Bỏ qua êm dịu, không ném lỗi")
    void testNullMailSender() {
        SmtpEmailSender nullSender = new SmtpEmailSender(null);
        assertDoesNotThrow(() -> nullSender.sendEmail(
                "no-reply@nexus.com", "to@nexus.com", "Subject", "<h1>Html</h1>", "Text"
        ));
    }

    @Test
    @DisplayName("Gửi thư thành công qua JavaMailSender")
    void testSendEmail_Success() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        smtpEmailSender.sendEmail("no-reply@nexus.com", "buyer@example.com", "Tiêu đề", "<p>Html</p>", "Text");

        verify(mailSender, times(1)).send(mimeMessage);
    }

    @Test
    @DisplayName("Khi JavaMailSender ném lỗi kết nối mạng (MailSendException): Chuyển thành EmailDeliveryException")
    void testSendEmail_ThrowsEmailDeliveryException() {
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        doThrow(new MailSendException("SMTP host connection refused"))
                .when(mailSender).send(any(MimeMessage.class));

        assertThrows(EmailDeliveryException.class, () -> smtpEmailSender.sendEmail(
                "no-reply@nexus.com", "buyer@example.com", "Tiêu đề", "<p>Html</p>", "Text"
        ));
    }
}
