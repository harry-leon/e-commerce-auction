package com.nexus.fulfillment.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ShipmentPreparationService;
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

/** PII snapshot chỉ dành Admin chuẩn bị kiện; Buyer/khách không được kích hoạt HTTP Commerce. */
@WebMvcTest(ShipmentPreparationController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ShipmentPreparationControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShipmentPreparationService service;

    /** Chặn trước service cho người không đủ quyền; Admin được preview, endpoint chưa dispatch. */
    @Test
    void requiresAdminBeforeCallingCommerce() throws Exception {
        String path = "/api/v1/shipments/preparation/" + UUID.randomUUID();
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isOk());
    }
}
