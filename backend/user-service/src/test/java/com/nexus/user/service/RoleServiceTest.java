package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.RoleResponse;
import com.nexus.user.dto.UpdateRoleRequest;
import com.nexus.user.entity.Privilege;
import com.nexus.user.entity.Role;
import com.nexus.user.repository.PrivilegeRepository;
import com.nexus.user.repository.RoleRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử đơn vị cho RoleService và phân quyền N-N.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PrivilegeRepository privilegeRepository;

    @Mock
    private AuditService auditService;

    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new RoleService(roleRepository, privilegeRepository, auditService);
    }

    @Test
    void testCreateRoleWithPrivileges() {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setCode("MANAGER");
        request.setName("Store Manager");
        request.setDescription("Manager Role");
        request.setPrivilegeCodes(Set.of("PRODUCT.CREATE", "PRODUCT.UPDATE"));

        when(roleRepository.existsByCode("MANAGER")).thenReturn(false);
        Privilege p1 = new Privilege(UUID.randomUUID(), "PRODUCT.CREATE", "Create product");
        Privilege p2 = new Privilege(UUID.randomUUID(), "PRODUCT.UPDATE", "Update product");
        when(privilegeRepository.findByCode("PRODUCT.CREATE")).thenReturn(Optional.of(p1));
        when(privilegeRepository.findByCode("PRODUCT.UPDATE")).thenReturn(Optional.of(p2));
        when(roleRepository.save(any(Role.class))).thenAnswer(i -> i.getArgument(0));

        RoleResponse response = roleService.createRole(request);

        assertNotNull(response);
        assertEquals("MANAGER", response.getCode());
        assertEquals("Store Manager", response.getName());
        assertEquals(2, response.getPrivileges().size());
        assertTrue(response.getPrivileges().contains("PRODUCT.CREATE"));
        assertTrue(response.getPrivileges().contains("PRODUCT.UPDATE"));
        verify(roleRepository).save(any(Role.class));
    }

    @Test
    void testGetAllRoles() {
        Role role1 = new Role(UUID.randomUUID(), "BUYER", "Buyer", "Buyer role");
        Role role2 = new Role(UUID.randomUUID(), "SELLER", "Seller", "Seller role");
        when(roleRepository.findAll()).thenReturn(List.of(role1, role2));

        List<RoleResponse> list = roleService.getAllRoles();
        assertEquals(2, list.size());
    }

    @Test
    void testUpdateRole() {
        UUID roleId = UUID.randomUUID();
        Role existingRole = new Role(roleId, "BUYER", "Old Buyer", "Desc");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(existingRole));
        when(roleRepository.save(any(Role.class))).thenAnswer(i -> i.getArgument(0));

        UpdateRoleRequest request = new UpdateRoleRequest();
        request.setName("Updated Buyer");
        request.setDescription("New Desc");
        request.setPrivilegeCodes(Set.of());

        RoleResponse response = roleService.updateRole(roleId, request);

        assertEquals("Updated Buyer", response.getName());
        assertEquals("New Desc", response.getDescription());
    }

    @Test
    void testDeleteAdminRoleThrowsException() {
        UUID roleId = UUID.randomUUID();
        Role adminRole = new Role(roleId, "ADMIN", "Administrator", "Desc");
        when(roleRepository.findById(roleId)).thenReturn(Optional.of(adminRole));

        assertThrows(IllegalArgumentException.class, () -> roleService.deleteRole(roleId));
    }
}
