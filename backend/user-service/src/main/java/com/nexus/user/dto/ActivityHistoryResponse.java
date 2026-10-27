package com.nexus.user.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DTO trả về một bản ghi lịch sử hoạt động của người dùng theo SRS §3.1.1.
 * Mỗi bản ghi được đọc từ payload của notification_svc.event_logs qua API nội bộ.
 *
 * @author Vinh Khang
 * @since 13/10/2026
 */
public class ActivityHistoryResponse {

    private UUID id;
    private String action;
    private String description;
    private String status;
    private LocalDateTime createdAt;

    /**
     * Khởi tạo đối tượng ActivityHistoryResponse mặc định.
     */
    public ActivityHistoryResponse() {
    }

    /**
     * Khởi tạo đối tượng ActivityHistoryResponse với đầy đủ thông tin.
     *
     * @param id          Mã định danh duy nhất của bản ghi lịch sử
     * @param action      Tên hành động (LOGIN, REGISTER, USER_UPDATED...)
     * @param description Mô tả chi tiết hành động
     * @param status      Trạng thái hành động (SUCCESS, FAILED)
     * @param createdAt   Thời điểm xảy ra hành động
     */
    public ActivityHistoryResponse(UUID id, String action, String description,
                                   String status, LocalDateTime createdAt) {
        this.id = id;
        this.action = action;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
