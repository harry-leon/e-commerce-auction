package com.nexus.user.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Entity đại diện cho vai trò (Role) trong hệ thống theo SRS mục 3.1.1.
 * Quan hệ N-N với Privilege thông qua bảng role_privileges.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @ManyToMany(fetch = FetchType.EAGER, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
            name = "role_privileges",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "privilege_id")
    )
    private Set<Privilege> privileges = new HashSet<>();

    /**
     * Khởi tạo đối tượng Role mặc định.
     */
    public Role() {
    }

    /**
     * Khởi tạo đối tượng Role với các thuộc tính cơ bản.
     *
     * @param id          Mã UUID của role
     * @param code        Mã code vai trò (ADMIN, SELLER, BUYER...)
     * @param name        Tên vai trò
     * @param description Mô tả vai trò
     */
    public Role(UUID id, String code, String name, String description) {
        this.id = id;
        this.code = code;
        this.name = name;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Privilege> getPrivileges() {
        return privileges;
    }

    public void setPrivileges(Set<Privilege> privileges) {
        this.privileges = privileges;
    }

    /**
     * Thêm một đặc quyền vào vai trò.
     *
     * @param privilege Đặc quyền cần thêm
     */
    public void addPrivilege(Privilege privilege) {
        if (privilege != null) {
            this.privileges.add(privilege);
        }
    }

    /**
     * Xóa một đặc quyền khỏi vai trò.
     *
     * @param privilege Đặc quyền cần xóa
     */
    public void removePrivilege(Privilege privilege) {
        if (privilege != null) {
            this.privileges.remove(privilege);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Role role = (Role) o;
        return Objects.equals(code, role.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }
}
