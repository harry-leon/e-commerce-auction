package com.nexus.notification.entity;

import com.nexus.notification.enums.AttemptResult;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Thực thể ghi nhận chi tiết từng lần thử gửi thông báo trong bảng notification_attempts (ERD Sprint 1).
 *
 * Chức năng:
 * - Lưu vết từng lần thử (attempt_no từ 1 đến tối đa NOTIFICATION_RETRY_LIMIT = 3).
 * - Ghi nhận kết quả: SUCCESS, RETRY, hoặc FAILURE.
 * - Lưu nội dung thông báo lỗi (error stack trace hoặc message) phục vụ chẩn đoán sự cố mạng/SMTP.
 */
@Entity
@Table(name = "notification_attempts")
public class NotificationAttempt {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private AttemptResult result;

    @Column(name = "error", columnDefinition = "text")
    private String error;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant attemptedAt;

    public NotificationAttempt() {
    }

    public NotificationAttempt(UUID id, Notification notification, int attemptNo,
                               AttemptResult result, String error, Instant attemptedAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.notification = notification;
        this.attemptNo = attemptNo;
        this.result = result;
        this.error = error;
        this.attemptedAt = attemptedAt != null ? attemptedAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Notification getNotification() {
        return notification;
    }

    public void setNotification(Notification notification) {
        this.notification = notification;
    }

    public int getAttemptNo() {
        return attemptNo;
    }

    public void setAttemptNo(int attemptNo) {
        this.attemptNo = attemptNo;
    }

    public AttemptResult getResult() {
        return result;
    }

    public void setResult(AttemptResult result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public void setAttemptedAt(Instant attemptedAt) {
        this.attemptedAt = attemptedAt;
    }
}
