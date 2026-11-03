package com.nexus.notification.entity;

import com.nexus.notification.enums.NotificationChannel;
import com.nexus.notification.enums.NotificationStatus;
import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Thực thể lưu trữ bản ghi thông báo trong bảng notifications (ERD Sprint 1).
 *
 * Chức năng:
 * - Lưu vết toàn bộ thông báo gửi tới người dùng trên các kênh (EMAIL, PUSH, IN_APP, SMS).
 * - Lưu trạng thái gửi hiện tại (PENDING, SENT, FAILED, READ) và thời điểm gửi thành công sent_at.
 * - Liên kết với event_id để hỗ trợ kiểm tra tính idempotent và truy vết nguồn gốc sự kiện.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_id", length = 50)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "sent_at")
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant sentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    public Notification() {
    }

    public Notification(UUID id, UUID userId, String eventId, NotificationChannel channel,
                        String title, String content, NotificationStatus status,
                        Instant sentAt, Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.eventId = eventId;
        this.channel = channel;
        this.title = title;
        this.content = content;
        this.status = status != null ? status : NotificationStatus.PENDING;
        this.sentAt = sentAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public void setChannel(NotificationChannel channel) {
        this.channel = channel;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public void setStatus(NotificationStatus status) {
        this.status = status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
