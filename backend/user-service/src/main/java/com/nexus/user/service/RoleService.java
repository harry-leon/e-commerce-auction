package com.nexus.user.service;

import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.RoleResponse;
import com.nexus.user.dto.UpdateRoleRequest;
import com.nexus.user.entity.NotFoundException;
import com.nexus.user.entity.Privilege;
import com.nexus.user.entity.Role;
import com.nexus.user.repository.PrivilegeRepository;
import com.nexus.user.repository.RoleRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service quản lý vai trò và phân quyền (Roles & Privileges N-N) theo SRS mục 3.1.1.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class RoleService {

    private final RoleRepository roleRepository;
    private final PrivilegeRepository privilegeRepository;
    private final AuditService auditService;

    /**
     * Khởi tạo RoleService với các Repository cần thiết.
     *
     * @param roleRepository      Repository vai trò
     * @param privilegeRepository Repository đặc quyền
     * @param auditService        Service ghi log kiểm toán
     */
    public RoleService(RoleRepository roleRepository,
                       PrivilegeRepository privilegeRepository,
                       AuditService auditService) {
        this.roleRepository = roleRepository;
        this.privilegeRepository = privilegeRepository;
        this.auditService = auditService;
    }

    /**
     * Lấy danh sách tất cả các vai trò trong hệ thống.
     *
     * @return Danh sách DTO RoleResponse
     */
    @Transactional(readOnly = true)
    public List<RoleResponse> getAllRoles() {
        return roleRepository.findAll().stream()
                .map(this::mapToRoleResponse)
                .collect(Collectors.toList());
    }

    /**
     * Lấy thông tin chi tiết một vai trò theo ID.
     *
     * @param id Mã định danh duy nhất của vai trò
     * @return DTO RoleResponse
     */
    @Transactional(readOnly = true)
    public RoleResponse getRoleById(UUID id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy vai trò với ID: " + id));
        return mapToRoleResponse(role);
    }

    /**
     * Tạo vai trò mới và gán danh sách đặc quyền (Role-Privilege N-N).
     *
     * @param request Yêu cầu tạo vai trò
     * @return DTO RoleResponse đã tạo
     */
    @Transactional
    public RoleResponse createRole(CreateRoleRequest request) {
        String code = request.getCode().trim().toUpperCase();
        if (roleRepository.existsByCode(code)) {
            throw new IllegalArgumentException("Mã vai trò đã tồn tại: " + code);
        }

        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(code);
        role.setName(request.getName().trim());
        role.setDescription(request.getDescription());

        if (request.getPrivilegeCodes() != null && !request.getPrivilegeCodes().isEmpty()) {
            Set<Privilege> privileges = new HashSet<>();
            for (String pCode : request.getPrivilegeCodes()) {
                privilegeRepository.findByCode(pCode.trim()).ifPresent(privileges::add);
            }
            role.setPrivileges(privileges);
        }

        Role savedRole = roleRepository.save(role);
        auditService.record(null, "ROLE_CREATED", "Tạo mới vai trò: " + code, null, null, "SUCCESS");

        return mapToRoleResponse(savedRole);
    }

    /**
     * Cập nhật thông tin vai trò và danh sách đặc quyền liên kết.
     *
     * @param id      Mã định danh duy nhất của vai trò
     * @param request Yêu cầu cập nhật
     * @return DTO RoleResponse sau khi cập nhật
     */
    @Transactional
    public RoleResponse updateRole(UUID id, UpdateRoleRequest request) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy vai trò với ID: " + id));

        role.setName(request.getName().trim());
        role.setDescription(request.getDescription());

        if (request.getPrivilegeCodes() != null) {
            Set<Privilege> privileges = new HashSet<>();
            for (String pCode : request.getPrivilegeCodes()) {
                privilegeRepository.findByCode(pCode.trim()).ifPresent(privileges::add);
            }
            role.setPrivileges(privileges);
        }

        Role updatedRole = roleRepository.save(role);
        auditService.record(null, "ROLE_UPDATED", "Cập nhật vai trò: " + role.getCode(), null, null, "SUCCESS");

        return mapToRoleResponse(updatedRole);
    }

    /**
     * Xóa một vai trò khỏi hệ thống theo ID.
     *
     * @param id Mã định danh duy nhất của vai trò
     */
    @Transactional
    public void deleteRole(UUID id) {
        Role role = roleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Không tìm thấy vai trò với ID: " + id));

        if ("ADMIN".equalsIgnoreCase(role.getCode())) {
            throw new IllegalArgumentException("Không được phép xóa vai trò quản trị hệ thống ADMIN");
        }

        roleRepository.delete(role);
        auditService.record(null, "ROLE_DELETED", "Xóa vai trò: " + role.getCode(), null, null, "SUCCESS");
    }

    /**
     * Chuyển đổi đối tượng Role Entity sang RoleResponse DTO.
     *
     * @param role Đối tượng Entity
     * @return DTO RoleResponse
     */
    public RoleResponse mapToRoleResponse(Role role) {
        Set<String> privilegeCodes = role.getPrivileges() != null
                ? role.getPrivileges().stream().map(Privilege::getCode).collect(Collectors.toSet())
                : new HashSet<>();

        return new RoleResponse(
                role.getId(),
                role.getCode(),
                role.getName(),
                role.getDescription(),
                privilegeCodes
        );
    }
}
