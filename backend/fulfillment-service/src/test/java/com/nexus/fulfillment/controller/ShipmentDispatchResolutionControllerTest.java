package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ShipmentDispatchResolutionService;
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

/** Recovery ảnh hưởng dữ liệu shipment: chỉ Admin, actor JWT UUID và key hợp lệ, không nhận actor do body khai. */
@WebMvcTest(ShipmentDispatchResolutionController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ShipmentDispatchResolutionControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShipmentDispatchResolutionService service;

    /** Khách/Buyer bị chặn trước service; Admin nhận audit định danh và thiếu key phải bị từ chối. */
    @Test
    void requiresAdminJwtActorAndIdempotencyKey() throws Exception {
        UUID attempt = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID();
        var result = new ShipmentDispatchResolutionService.Result(UUID.randomUUID(), UUID.randomUUID());
        String path = "/api/v1/shipments/dispatches/" + attempt + "/manual-resolution";
        String body = "{\"trackingNumber\":\"TRACK-TEST\",\"shippingFee\":25000,\"evidenceReference\":\"CASE-TEST\",\"note\":\"Đã đối soát\"}";
        mvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(service);
        when(service.resolve(eq(attempt), eq(actor), eq(key), any())).thenReturn(result);
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auditId").value(result.auditId().toString()));
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(path).with(jwt().jwt(j -> j.subject("not-a-uuid")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
