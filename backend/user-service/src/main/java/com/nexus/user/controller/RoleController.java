package com.nexus.user.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.RoleResponse;
import com.nexus.user.dto.UpdateRoleRequest;
import com.nexus.user.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller giữ contract quản lý vai trò; RoleService gọi realm roles/composites của Keycloak.
 * Role ID do IAM cấp, không còn đọc bảng roles/privileges trong database profile.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@RestController
@RequestMapping("/api/v1/roles")
@Tag(name = "Role & Privilege Management", description = "Các API quản lý vai trò và phân quyền (N-N)")
public class RoleController {

    private final RoleService roleService;

    /**
     * Khởi tạo RoleController với RoleService.
     *
     * @param roleService Service quản lý vai trò
     */
    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    /**
     * Lấy danh sách toàn bộ các vai trò cùng đặc quyền trong hệ thống.
     *
     * @return Danh sách RoleResponse
     */
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE.LIST')")
    @Operation(summary = "Danh sách vai trò", description = "Lấy tất cả các vai trò và quyền trong hệ thống")
    public ResponseEntity<ApiResponse<List<RoleResponse>>> getAllRoles() {
        return ResponseEntity.ok(ApiResponse.ok(roleService.getAllRoles()));
    }

    /**
     * Xem thông tin chi tiết một vai trò theo ID.
     *
     * @param id Mã định danh của vai trò
     * @return DTO RoleResponse
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE.VIEW')")
    @Operation(summary = "Xem chi tiết vai trò", description = "Lấy thông tin chi tiết vai trò theo ID")
    public ResponseEntity<ApiResponse<RoleResponse>> getRoleById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(roleService.getRoleById(id)));
    }

    /**
     * Tạo một vai trò mới trong hệ thống.
     *
     * @param request Dữ liệu tạo vai trò
     * @return DTO RoleResponse với HTTP 201 Created
     */
    @PostMapping
    @PreAuthorize("hasAuthority('ROLE.CREATE')")
    @Operation(summary = "Tạo vai trò mới", description = "Tạo realm role và gán composite privileges tại Keycloak")
    public ResponseEntity<ApiResponse<RoleResponse>> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse response = roleService.createRole(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(response));
    }

    /**
     * Cập nhật thông tin vai trò và danh sách đặc quyền liên kết.
     *
     * @param id      Mã định danh của vai trò
     * @param request Dữ liệu cập nhật
     * @return DTO RoleResponse sau cập nhật
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE.UPDATE')")
    @Operation(summary = "Cập nhật vai trò", description = "Cập nhật tên, mô tả và quyền của vai trò")
    public ResponseEntity<ApiResponse<RoleResponse>> updateRole(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRoleRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(roleService.updateRole(id, request)));
    }

    /**
     * Xóa một vai trò khỏi hệ thống theo ID.
     *
     * @param id Mã định danh của vai trò
     * @return Thông báo kết quả
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE.DELETE')")
    @Operation(summary = "Xóa vai trò", description = "Xóa vai trò khỏi hệ thống (ngoại trừ ADMIN)")
    public ResponseEntity<ApiResponse<Map<String, String>>> deleteRole(@PathVariable UUID id) {
        roleService.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("message", "Đã xóa vai trò thành công")));
    }
}
