package com.nexus.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình tham số cho phân hệ Notification theo SRS §2.6 và §3.2.3.
 *
 * Chức năng:
 * - EMAIL_NOTIFICATION_ENABLED: Bật/tắt gửi email giao dịch (mặc định: true).
 * - NOTIFICATION_RETRY_LIMIT: Số lần thử lại tối đa khi gửi email gặp sự cố mạng (mặc định: 3).
 * - TRANSACTIONAL_NOTIFICATION_MANDATORY: Bắt buộc gửi email giao dịch (mặc định: true).
 * - senderFrom: Địa chỉ người gửi hiển thị trên email hóa đơn / thông báo giao dịch.
 *
 * Lý do & Quy tắc:
 * - Ánh xạ từ tiền tố cấu hình "notification.*" trong file cấu hình application.yml và biến môi trường.
 */
@Configuration
@ConfigurationProperties(prefix = "notification")
public class NotificationProperties {

    /** Cấu hình bật/tắt gửi email giao dịch (SRS §2.6 EMAIL_NOTIFICATION_ENABLED). */
    private boolean emailEnabled = true;

    /** Số lần thử lại tối đa đối với các lỗi tạm thời khi gửi email (SRS §2.6 NOTIFICATION_RETRY_LIMIT = 3). */
    private int retryLimit = 3;

    /** Đánh dấu kênh email giao dịch là bắt buộc (SRS §2.6 TRANSACTIONAL_NOTIFICATION_MANDATORY = true). */
    private boolean transactionalMandatory = true;

    /** Địa chỉ email người gửi (FROM header). */
    private String senderFrom = "no-reply@nexus.com";

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(int retryLimit) {
        this.retryLimit = retryLimit;
    }

    public boolean isTransactionalMandatory() {
        return transactionalMandatory;
    }

    public void setTransactionalMandatory(boolean transactionalMandatory) {
        this.transactionalMandatory = transactionalMandatory;
    }

    public String getSenderFrom() {
        return senderFrom;
    }

    public void setSenderFrom(String senderFrom) {
        this.senderFrom = senderFrom;
    }
}
