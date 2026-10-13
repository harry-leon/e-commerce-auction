package com.nexus.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.user.config.SecurityConfig;
import com.nexus.user.dto.AuthResponse;
import com.nexus.user.dto.ForgotPasswordRequest;
import com.nexus.user.dto.LoginRequest;
import com.nexus.user.dto.RefreshTokenRequest;
import com.nexus.user.dto.RegisterRequest;
import com.nexus.user.dto.ResetPasswordRequest;
import com.nexus.user.dto.UserResponse;
import com.nexus.user.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử WebMvc cho AuthController.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @Test
    void testRegisterEndpoint() throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("newuser@nexus.com");
        request.setPassword("Password123");
        request.setFullName("Nguyen Van Test");

        UserResponse userResponse = new UserResponse();
        userResponse.setEmail("newuser@nexus.com");
        AuthResponse authResponse = new AuthResponse("token1", "refresh1", 3600L, userResponse);

        when(authService.register(any(RegisterRequest.class), any(), any())).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("token1"))
                .andExpect(jsonPath("$.refreshToken").value("refresh1"));
    }

    @Test
    void testLoginEndpoint() throws Exception {
        LoginRequest request = new LoginRequest("test@nexus.com", "Password123");
        UserResponse userResponse = new UserResponse();
        userResponse.setEmail("test@nexus.com");
        AuthResponse authResponse = new AuthResponse("access123", "refresh123", 3600L, userResponse);

        when(authService.login(any(LoginRequest.class), any(), any())).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access123"));
    }

    @Test
    void testRefreshEndpoint() throws Exception {
        RefreshTokenRequest request = new RefreshTokenRequest("valid_refresh_token");
        AuthResponse authResponse = new AuthResponse("new_access", "new_refresh", 3600L, new UserResponse());

        when(authService.refreshToken(any(RefreshTokenRequest.class))).thenReturn(authResponse);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new_access"));
    }

    @Test
    void testForgotPasswordEndpoint() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("user@nexus.com");

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void testResetPasswordEndpoint() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("token123", "NewPass123");

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").exists());
    }
}
