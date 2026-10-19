package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/** Kiểm tra thư gửi tới JavaMailSender có đúng người nhận và URL mã hóa token; không kết nối SMTP thật trong unit test. */
class EmailServiceTest {
    @Test
    void sendsResetLinkThroughMailSender() {
        JavaMailSender sender = mock(JavaMailSender.class);
        new EmailService(sender, "noreply@nexus.local", "https://nexus.example/")
                .sendPasswordResetEmail("buyer@example.com", "token+value");
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertEquals("buyer@example.com", message.getValue().getTo()[0]);
        assertTrue(message.getValue().getText().contains("https://nexus.example/forgot-password?token=token%2Bvalue"));
    }
}
