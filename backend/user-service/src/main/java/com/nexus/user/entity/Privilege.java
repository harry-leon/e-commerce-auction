package com.nexus.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity đại diện cho đặc quyền (Privilege) trong hệ thống theo SRS mục 2.7.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Entity
@Table(name = "privileges")
public class Privilege {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "description", length = 500)
    private String description;

    /**
     * Khởi tạo đối tượng Privilege mặc định.
     */
    public Privilege() {
    }

    /**
     * Khởi tạo đối tượng Privilege với các thông tin chi tiết.
     *
     * @param id          Mã định danh duy nhất của Privilege
     * @param code        Mã code đặc quyền (ví dụ: AUTH.LOGIN, PRODUCT.CREATE)
     * @param description Mô tả chi tiết đặc quyền
     */
    public Privilege(UUID id, String code, String description) {
        this.id = id;
        this.code = code;
        this.description = description;
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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Privilege privilege = (Privilege) o;
        return Objects.equals(code, privilege.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
