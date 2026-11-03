package com.nexus.notification.service;

import com.nexus.notification.exception.EmailDeliveryException;

/**
 * Giao diện phát gửi email thực tế qua nhà cung cấp SMTP / Email Provider (SRS §3.2.3).
 *
 * Chức năng:
 * - Gửi email dưới định dạng HTML kết hợp Plain-Text dự phòng.
 * - Cho phép dễ dàng thay thế giữa SmtpEmailSender thực tế và MockEmailSender trong môi trường Unit/Integration Test.
 *
 * Ngoại lệ:
 * - Ném EmailDeliveryException khi gặp lỗi mạng/máy chủ SMTP tạm thời để kích hoạt cơ chế retry.
 */
public interface EmailSender {

    /**
     * Gửi email tới người nhận.
     *
     * @param from     Địa chỉ người gửi
     * @param to       Địa chỉ người nhận
     * @param subject  Tiêu đề thư
     * @param bodyHtml Nội dung định dạng HTML
     * @param bodyText Nội dung văn bản thô (Plain Text)
     * @throws EmailDeliveryException Khi máy chủ SMTP từ chối hoặc lỗi mạng tạm thời
     */
    void sendEmail(String from, String to, String subject, String bodyHtml, String bodyText)
            throws EmailDeliveryException;
}
