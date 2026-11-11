package com.nexus.user.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.user.config.SecurityConfig;
import com.nexus.user.dto.UserContactResponse;
import com.nexus.user.service.UserService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra bảo mật ServiceIdentity và phản hồi API tra cứu liên hệ nội bộ cho Commerce. */
@WebMvcTest(UserInternalController.class)
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class UserInternalControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Chỉ nexus-commerce-service kèm quyền USER.INTERNAL_READ mới được đọc liên hệ winner; role thường bị từ chối. */
    @Test
    @DisplayName("Endpoint nội bộ user chỉ cho phép nexus-commerce-service có USER.INTERNAL_READ")
    void enforcesServiceIdentityAndReturnsContact() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.getInternalContact(userId))
                .thenReturn(new UserContactResponse(userId, "winner@nexus.test", "Người thắng"));

        mvc.perform(get("/api/v1/internal/users/{id}", userId))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/internal/users/{id}", userId)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/internal/users/{id}", userId)
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                                .authorities(new SimpleGrantedAuthority("USER.INTERNAL_READ"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/internal/users/{id}", userId)
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-commerce-service"))
                                .authorities(new SimpleGrantedAuthority("USER.INTERNAL_READ"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("winner@nexus.test"))
                .andExpect(jsonPath("$.data.fullName").value("Người thắng"));
    }
}
