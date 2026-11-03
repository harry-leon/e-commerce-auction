package com.nexus.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.user.config.SecurityConfig;
import com.nexus.user.dto.ActivityHistoryResponse;
import com.nexus.user.dto.UpdateUserRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.service.UserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử WebMvc cho UserController.
 * Xác thực các endpoint người dùng trả về chuẩn Envelope ApiResponse ({success, data, error, meta}).
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class UserControllerTest {

    /** Các API /me phải tra sub qua keycloak_id trước khi dùng profile ID. */
    @org.junit.jupiter.api.BeforeEach
    void resolveProfileSubjects() {
        org.mockito.Mockito.lenient().when(userService.findBySubject(any())).thenAnswer(invocation -> {
            var profile = new com.nexus.user.entity.User();
            profile.setId(UUID.fromString(invocation.getArgument(0)));
            return profile;
        });
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @Test
    void testGetMyProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        UserResponse userResponse = new UserResponse();
        userResponse.setId(userId);
        userResponse.setEmail("me@nexus.com");

        when(userService.getUserById(userId)).thenReturn(userResponse);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("PROFILE.VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("me@nexus.com"));
    }

    @Test
    void testUpdateMyProfile() throws Exception {
        UUID userId = UUID.randomUUID();
        UpdateUserRequest request = new UpdateUserRequest();
        request.setFullName("Updated My Name");

        UserResponse userResponse = new UserResponse();
        userResponse.setId(userId);
        userResponse.setFullName("Updated My Name");

        when(userService.updateUser(eq(userId), any())).thenReturn(userResponse);

        mockMvc.perform(put("/api/v1/users/me")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("PROFILE.UPDATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fullName").value("Updated My Name"));
    }

    @Test
    void testGetUserById() throws Exception {
        UUID userId = UUID.randomUUID();
        UserResponse userResponse = new UserResponse();
        userResponse.setId(userId);
        userResponse.setEmail("user1@nexus.com");

        when(userService.getUserById(userId)).thenReturn(userResponse);

        mockMvc.perform(get("/api/v1/users/" + userId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("USER.VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("user1@nexus.com"));
    }

    @Test
    void testListUsers() throws Exception {
        UserResponse u1 = new UserResponse();
        u1.setEmail("u1@nexus.com");
        when(userService.searchUsers(any(), any())).thenReturn(new PageImpl<>(List.of(u1)));

        mockMvc.perform(get("/api/v1/users")
                        .with(jwt().authorities(new SimpleGrantedAuthority("USER.LIST"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].email").value("u1@nexus.com"))
                .andExpect(jsonPath("$.meta.pagination").exists());
    }

    @Test
    void testDeleteUser() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/users/" + userId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("USER.DELETE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.message").exists());
    }

    @Test
    void testAssignRoles() throws Exception {
        UUID userId = UUID.randomUUID();
        UserResponse response = new UserResponse();
        response.setRoles(Set.of("SELLER"));

        when(userService.assignRoles(eq(userId), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/users/" + userId + "/roles")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Set.of("SELLER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.roles[0]").value("SELLER"));
    }

    @Test
    void testGetMyActivityHistory() throws Exception {
        UUID userId = UUID.randomUUID();
        ActivityHistoryResponse item = new ActivityHistoryResponse(
                UUID.randomUUID(), "LOGIN", "Dang nhap", "SUCCESS", LocalDateTime.now()
        );
        when(userService.getActivityHistory(eq(userId), any())).thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/v1/users/me/activity-history")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("PROFILE.VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].action").value("LOGIN"))
                .andExpect(jsonPath("$.meta.pagination").exists());
    }

    @Test
    void testGetUserActivityHistory() throws Exception {
        UUID userId = UUID.randomUUID();
        ActivityHistoryResponse item = new ActivityHistoryResponse(
                UUID.randomUUID(), "USER_UPDATE", "Cap nhat", "SUCCESS", LocalDateTime.now()
        );
        when(userService.getActivityHistory(eq(userId), any())).thenReturn(new PageImpl<>(List.of(item)));

        mockMvc.perform(get("/api/v1/users/" + userId + "/activity-history")
                        .with(jwt().authorities(new SimpleGrantedAuthority("USER.VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].action").value("USER_UPDATE"))
                .andExpect(jsonPath("$.meta.pagination").exists());
    }
}
