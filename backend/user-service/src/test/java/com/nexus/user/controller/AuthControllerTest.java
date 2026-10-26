package com.nexus.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.user.config.SecurityConfig;
import com.nexus.user.entity.User;
import com.nexus.user.repository.UserRepository;
import com.nexus.user.service.KeycloakClient;
import com.nexus.user.service.UserService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Reset dùng action email Keycloak; change password bắt buộc principal JWT và profile đã liên kết. */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private UserService users;
    @MockitoBean private UserRepository profiles;
    @MockitoBean private KeycloakClient keycloak;

    @Test
    void resetEmailsUseSamePublicResponseForMissingAndExistingProfiles() throws Exception {
        var user = new User();
        user.setKeycloakId(UUID.randomUUID());
        when(profiles.findByEmailAndIsDeletedFalse(any())).thenReturn(Optional.empty(), Optional.of(user));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"user@example.com\"}")).andExpect(status().isOk());
        }
        verify(keycloak).sendResetEmail(user.getKeycloakId());
        mvc.perform(post("/api/v1/auth/reset-password")).andExpect(status().isGone());
    }

    @Test
    void passwordChangeRequiresVerifiedJwt() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}")).andExpect(status().isUnauthorized());
        var user = new User();
        user.setKeycloakId(UUID.randomUUID());
        user.setEmail("user@example.com");
        when(users.findBySubject(any())).thenReturn(user);
        mvc.perform(post("/api/v1/auth/change-password").with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"old-pass\",\"newPassword\":\"new-pass\"}")).andExpect(status().isOk());
        verify(keycloak).changePassword(any(), org.mockito.ArgumentMatchers.eq("old-pass"), org.mockito.ArgumentMatchers.eq("new-pass"));
    }
}
