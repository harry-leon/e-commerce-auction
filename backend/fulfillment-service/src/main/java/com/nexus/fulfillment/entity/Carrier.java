package com.nexus.fulfillment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Thực thể đơn vị vận chuyển đối tác (GHN, GHTK, ...) ánh xạ bảng carriers (SRS §3.6.2).
 * Dùng để liên kết khóa ngoại carrier_id trên vận đơn và kiểm soát trạng thái kích hoạt của từng hãng.
 */
@Entity
@Table(name = "carriers")
public class Carrier {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    public Carrier() {
    }

    public Carrier(UUID id, String code, String name, boolean isActive) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.isActive = isActive;
    }

    @PrePersist
    void ensureId() {
        if (this.id == null) {
            this.id = UUID.randomUUID();
        }
        if (this.code != null) {
            this.code = this.code.trim().toUpperCase();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Carrier that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
