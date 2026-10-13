package com.nexus.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.user.config.SecurityConfig;
import com.nexus.user.dto.CreateRoleRequest;
import com.nexus.user.dto.RoleResponse;
import com.nexus.user.dto.UpdateRoleRequest;
import com.nexus.user.service.RoleService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử WebMvc cho RoleController.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@WebMvcTest(RoleController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class RoleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RoleService roleService;

    @Test
    void testGetAllRoles() throws Exception {
        RoleResponse r1 = new RoleResponse(UUID.randomUUID(), "BUYER", "Buyer", "Desc", Set.of());
        when(roleService.getAllRoles()).thenReturn(List.of(r1));

        mockMvc.perform(get("/api/v1/roles")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE.LIST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("BUYER"));
    }

    @Test
    void testGetRoleById() throws Exception {
        UUID roleId = UUID.randomUUID();
        RoleResponse r1 = new RoleResponse(roleId, "BUYER", "Buyer", "Desc", Set.of());
        when(roleService.getRoleById(roleId)).thenReturn(r1);

        mockMvc.perform(get("/api/v1/roles/" + roleId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE.VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BUYER"));
    }

    @Test
    void testCreateRole() throws Exception {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setCode("MODERATOR");
        request.setName("Moderator");
        request.setDescription("Moderator Role");

        RoleResponse response = new RoleResponse(UUID.randomUUID(), "MODERATOR", "Moderator", "Desc", Set.of());
        when(roleService.createRole(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/roles")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE.CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("MODERATOR"));
    }

    @Test
    void testUpdateRole() throws Exception {
        UUID roleId = UUID.randomUUID();
        UpdateRoleRequest request = new UpdateRoleRequest();
        request.setName("Updated Mod");

        RoleResponse response = new RoleResponse(roleId, "MODERATOR", "Updated Mod", "Desc", Set.of());
        when(roleService.updateRole(eq(roleId), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/roles/" + roleId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE.UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Mod"));
    }

    @Test
    void testDeleteRole() throws Exception {
        UUID roleId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/roles/" + roleId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE.DELETE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
