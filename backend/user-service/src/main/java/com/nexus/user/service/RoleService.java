package com.nexus.user.service;

import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.RoleResponse;
import com.nexus.user.dto.UpdateRoleRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Giữ contract /roles nhưng chuyển lưu trữ sang realm roles/composites của Keycloak, không còn bảng IAM cục bộ. */
@Service
public class RoleService {
    private final KeycloakClient keycloak;

    public RoleService(KeycloakClient keycloak) { this.keycloak = keycloak; }

    /** Liệt kê vai trò ứng dụng và privilege hiệu lực từ IAM. */
    public List<RoleResponse> getAllRoles() { return keycloak.roles().stream().map(this::mapRole).toList(); }

    /** UUID endpoint là ID role do Keycloak cấp. */
    public RoleResponse getRoleById(UUID id) { return mapRole(find(id)); }

    private KeycloakClient.RealmRole find(UUID id) {
        return keycloak.roles().stream().filter(role -> role.id().equals(id.toString())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy vai trò"));
    }

    /** Tạo role IAM; validate privilege trước, rollback role mới nếu gán composite thất bại. */
    public RoleResponse createRole(CreateRoleRequest request) {
        String code = request.getCode().trim().toUpperCase(java.util.Locale.ROOT);
        String name = code.startsWith("ROLE_") ? code : "ROLE_" + code;
        if (!name.matches("ROLE_[A-Z0-9_]+")) { throw new IllegalArgumentException("Mã vai trò không hợp lệ"); }
        validatePrivileges(request.getPrivilegeCodes());
        keycloak.mutate("POST", "/roles", Map.of("name", name, "description",
                request.getDescription() == null ? "" : request.getDescription(),
                "attributes", Map.of("displayName", List.of(request.getName().trim()))));
        var role = keycloak.roles().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
        try {
            replacePrivileges(role, request.getPrivilegeCodes());
        } catch (RuntimeException error) {
            try { keycloak.mutate("DELETE", "/roles-by-id/" + role.id(), Map.of()); }
            catch (RuntimeException rollbackError) { error.addSuppressed(rollbackError); }
            throw error;
        }
        return mapRole(role);
    }

    /** Cập nhật metadata/privileges bằng Admin API; remote mutation không thuộc transaction PostgreSQL. */
    public RoleResponse updateRole(UUID id, UpdateRoleRequest request) {
        var role = find(id);
        validatePrivileges(request.getPrivilegeCodes());
        keycloak.mutate("PUT", "/roles-by-id/" + id, Map.of("name", role.name(), "description",
                request.getDescription() == null ? "" : request.getDescription(),
                "attributes", Map.of("displayName", List.of(request.getName().trim()))));
        if (request.getPrivilegeCodes() != null) { replacePrivileges(role, request.getPrivilegeCodes()); }
        return getRoleById(id);
    }

    /** Giữ bốn role nền tảng để không phá default role và các policy có sẵn. */
    public void deleteRole(UUID id) {
        var role = find(id);
        if (Set.of("ROLE_ADMIN", "ROLE_BUYER", "ROLE_SELLER", "ROLE_SUPPORT").contains(role.name())) {
            throw new IllegalArgumentException("Không thể xóa vai trò nền tảng");
        }
        keycloak.mutate("DELETE", "/roles-by-id/" + id, Map.of());
    }

    private void validatePrivileges(Set<String> codes) {
        if (codes != null && !keycloak.privilegeRoles().stream().map(KeycloakClient.RealmRole::name)
                .collect(java.util.stream.Collectors.toSet()).containsAll(codes)) {
            throw new IllegalArgumentException("Có đặc quyền không tồn tại");
        }
    }

    /** Thêm quyền mong muốn trước, xóa quyền dư sau; chỉ xử lý privilege nguyên tử chứa dấu chấm. */
    private void replacePrivileges(KeycloakClient.RealmRole role, Set<String> codes) {
        Set<String> requested = codes == null ? Set.of() : codes;
        var all = keycloak.privilegeRoles();
        var desired = all.stream().filter(item -> requested.contains(item.name())).toList();
        Set<String> current = keycloak.privileges(role.id());
        keycloak.mutate("POST", "/roles-by-id/" + role.id() + "/composites", desired);
        var removed = all.stream().filter(item -> current.contains(item.name()) && !requested.contains(item.name())).toList();
        if (!removed.isEmpty()) { keycloak.mutate("DELETE", "/roles-by-id/" + role.id() + "/composites", removed); }
    }

    private RoleResponse mapRole(KeycloakClient.RealmRole role) {
        String code = role.name().substring(5);
        String display = role.attributes() == null ? code :
                role.attributes().getOrDefault("displayName", List.of(code)).stream().findFirst().orElse(code);
        return new RoleResponse(UUID.fromString(role.id()), code, display, role.description(), keycloak.privileges(role.id()));
    }
}
