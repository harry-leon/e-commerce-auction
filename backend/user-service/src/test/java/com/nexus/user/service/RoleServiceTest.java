package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.UpdateRoleRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Role API tiếp tục phục vụ UI qua Keycloak; không còn phụ thuộc bảng roles đã bị drop. */
@ExtendWith(MockitoExtension.class)
class RoleServiceTest {
    @Mock private KeycloakClient client;

    @Test
    void listsCreatesAndUpdatesApplicationRoles() {
        UUID id = UUID.randomUUID();
        var role = new KeycloakClient.RealmRole(id.toString(), "ROLE_CUSTOM", "Mô tả", Map.of("displayName", List.of("Custom")));
        var privilege = new KeycloakClient.RealmRole(UUID.randomUUID().toString(), "PROFILE.VIEW", "", Map.of());
        when(client.roles()).thenReturn(List.of(role));
        when(client.privileges(id.toString())).thenReturn(Set.of("PROFILE.VIEW"));
        when(client.privilegeRoles()).thenReturn(List.of(privilege));
        var service = new RoleService(client);
        assertEquals("CUSTOM", service.getAllRoles().getFirst().getCode());
        var create = new CreateRoleRequest();
        create.setCode("custom");
        create.setName("Custom");
        create.setPrivilegeCodes(Set.of("PROFILE.VIEW"));
        assertEquals("Custom", service.createRole(create).getName());
        var update = new UpdateRoleRequest();
        update.setName("New display");
        update.setPrivilegeCodes(Set.of());
        assertEquals(id, service.updateRole(id, update).getId());
        service.deleteRole(id);
        verify(client).mutate(eq("DELETE"), eq("/roles-by-id/" + id), any());
    }

    @Test
    void protectsBuiltInRolesAndRejectsUnknownPrivileges() {
        UUID id = UUID.randomUUID();
        when(client.roles()).thenReturn(List.of(new KeycloakClient.RealmRole(id.toString(), "ROLE_ADMIN", "", Map.of())));
        var service = new RoleService(client);
        assertThrows(IllegalArgumentException.class, () -> service.deleteRole(id));
        assertThrows(com.nexus.common.exception.ResourceNotFoundException.class, () -> service.getRoleById(UUID.randomUUID()));
        var request = new CreateRoleRequest();
        request.setCode("NEW");
        request.setName("New");
        request.setPrivilegeCodes(Set.of("UNKNOWN.PRIVILEGE"));
        assertThrows(IllegalArgumentException.class, () -> service.createRole(request));
    }
}
