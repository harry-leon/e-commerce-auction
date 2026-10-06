package com.nexus.user.dto;

import java.util.Set;
import java.util.UUID;

/**
 * DTO trả về thông tin chi tiết vai trò cùng danh sách đặc quyền (Privileges).
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class RoleResponse {

    private UUID id;
    private String code;
    private String name;
    private String description;
    private Set<String> privileges;

    /**
     * Khởi tạo đối tượng RoleResponse mặc định.
     */
    public RoleResponse() {
    }

    /**
     * Khởi tạo đối tượng RoleResponse với đầy đủ các thuộc tính.
     *
     * @param id          Mã UUID của vai trò
     * @param code        Mã code vai trò
     * @param name        Tên vai trò
     * @param description Mô tả chi tiết vai trò
     * @param privileges  Tập hợp mã code đặc quyền thuộc vai trò
     */
    public RoleResponse(UUID id, String code, String name, String description, Set<String> privileges) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.description = description;
        this.privileges = privileges;
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

    public Set<String> getPrivileges() {
        return privileges;
    }

    public void setPrivileges(Set<String> privileges) {
        this.privileges = privileges;
    }
}
