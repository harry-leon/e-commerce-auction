package com.nexus.fulfillment.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ShippingEventReplayService;
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

/** API replay event cần Admin/key/actor JWT UUID; Buyer/khách không được reset backlog. */
@WebMvcTest(ShippingEventReplayController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ShippingEventReplayControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingEventReplayService service;

    /** Chặn trước service khi thiếu quyền; Admin nhận auditId, thiếu key 400 hoặc subject sai 401. */
    @Test
    void requiresAdminKeyAndJwtActor() throws Exception {
        UUID id = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID(); UUID audit = UUID.randomUUID();
        String path = "/api/v1/shipments/events/" + id + "/retry"; String body = "{\"reason\":\"Đã sửa IAM\"}";
        mvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.replay(id, actor, key, "Đã sửa IAM")).thenReturn(audit);
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditId").value(audit.toString()));
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject("invalid")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
