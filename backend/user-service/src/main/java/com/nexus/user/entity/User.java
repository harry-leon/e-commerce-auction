package com.nexus.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Hồ sơ nghiệp vụ theo ERD V2; Keycloak sở hữu mật khẩu, phiên và vai trò.
 * Chỉ ánh xạ bảng users sau V4/V5, không truy cập bảng IAM hoặc Reputation đã chuyển service.
 */
@Entity
@Table(name = "users")
public class User {
    @Id
    private UUID id;
    // Bắt buộc từ V8__require_keycloak_id: mọi hồ sơ phải liên kết tài khoản Keycloak để đăng nhập/đổi mật khẩu.
    @Column(name = "keycloak_id", nullable = false, unique = true)
    private UUID keycloakId;
    @Column(nullable = false, unique = true, length = 255)
    private String email;
    @Column(length = 20)
    private String phone;
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;
    @Column(name = "identify_number", length = 30)
    private String identifyNumber;
    @Column(length = 10)
    private String gender;
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    @Column(length = 500)
    private String address;
    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";
    @Column(name = "is_deleted", nullable = false)
    private boolean isDeleted;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /** Đặt mốc tạo/cập nhật khi persist; update chỉ thay updated_at, giữ nguyên lịch sử tạo. */
    @PrePersist
    public void onCreate() {
        if (createdAt == null) { createdAt = LocalDateTime.now(); }
        updatedAt = createdAt;
    }

    /** Đồng bộ thời điểm sửa hồ sơ trong cùng transaction JPA. */
    @PreUpdate
    public void onUpdate() { updatedAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public void setId(UUID value) { id = value; }
    public UUID getKeycloakId() { return keycloakId; }
    public void setKeycloakId(UUID value) { keycloakId = value; }
    public String getEmail() { return email; }
    public void setEmail(String value) { email = value; }
    public String getPhone() { return phone; }
    public void setPhone(String value) { phone = value; }
    public String getFullName() { return fullName; }
    public void setFullName(String value) { fullName = value; }
    public String getIdentifyNumber() { return identifyNumber; }
    public void setIdentifyNumber(String value) { identifyNumber = value; }
    public String getGender() { return gender; }
    public void setGender(String value) { gender = value; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate value) { dateOfBirth = value; }
    public String getAddress() { return address; }
    public void setAddress(String value) { address = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public boolean isDeleted() { return isDeleted; }
    public void setDeleted(boolean value) { isDeleted = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { updatedAt = value; }
}
