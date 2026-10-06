package com.nexus.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Set;

/**
 * DTO nhận yêu cầu cập nhật vai trò theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
public class UpdateRoleRequest {

    @NotBlank(message = "Tên vai trò không được để trống")
    @Size(max = 100, message = "Tên vai trò tối đa 100 ký tự")
    private String name;

    @Size(max = 500, message = "Mô tả tối đa 500 ký tự")
    private String description;

    private Set<String> privilegeCodes;

    /**
     * Khởi tạo đối tượng UpdateRoleRequest mặc định.
     */
    public UpdateRoleRequest() {
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

    public Set<String> getPrivilegeCodes() {
        return privilegeCodes;
    }

    public void setPrivilegeCodes(Set<String> privilegeCodes) {
        this.privilegeCodes = privilegeCodes;
    }
}
