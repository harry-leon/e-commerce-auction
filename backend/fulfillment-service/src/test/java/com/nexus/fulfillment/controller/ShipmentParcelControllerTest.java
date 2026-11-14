package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.common.exception.ApiException;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ShipmentParcelService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Admin là nguồn actor duy nhất; người mua/service token không được sửa kiện hoặc đọc audit nội bộ. */
@WebMvcTest(ShipmentParcelController.class)
@Import({SecurityConfig.class, ShipmentDispatchExceptionHandler.class})
@ActiveProfiles("test")
class ShipmentParcelControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShipmentParcelService service;

    /** Khách/Buyer/service bị chặn ở cả GET/POST trước service, không gây DB/network side effect. */
    @Test
    void requiresAdmin() throws Exception {
        String path = path(UUID.randomUUID());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String role : new String[]{"ROLE_BUYER", "SHIPPING.ORDER_PAID"}) {
            mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden());
            mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(role))).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    /** Actor lấy JWT, key lấy header; created/replay trả 201/200 và GET giữ cùng audit, không trả tracking giả. */
    @Test
    void returnsRegistrationAndReplayWithJwtActor() throws Exception {
        UUID order = UUID.randomUUID(); UUID actor = UUID.randomUUID(); UUID key = UUID.randomUUID(); UUID id = UUID.randomUUID();
        var registration = new ShipmentParcelService.Registration(id, order, null, actor, Instant.now());
        when(service.register(eq(order), eq(actor), eq(key), any())).thenReturn(new ShipmentParcelService.Result(registration, true),
                new ShipmentParcelService.Result(registration, false));
        for (int expected : new int[]{201, 200}) {
            mvc.perform(post(path(order)).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                    .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.data.id").value(id.toString()))
                    .andExpect(jsonPath("$.data.registeredBy").value(actor.toString())).andExpect(jsonPath("$.data.trackingNumber").doesNotExist());
        }
        when(service.get(order)).thenReturn(registration);
        mvc.perform(get(path(order)).with(jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id.toString()));
    }

    /** UUID/key/subject sai bị chặn; business 409 giữ status/envelope, không bị handler chung biến thành 500. */
    @Test
    void validatesMetadataAndPreservesBusinessError() throws Exception {
        UUID order = UUID.randomUUID(); UUID actor = UUID.randomUUID();
        var admin = jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        mvc.perform(post(path(order)).with(admin).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(get(path(order)).with(jwt().jwt(j -> j.subject("bad")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.register(eq(order), eq(actor), any(), any())).thenThrow(new ApiException("PARCEL_CONFLICT", HttpStatus.CONFLICT, "Fixture", null));
        mvc.perform(post(path(order)).with(admin).header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("PARCEL_CONFLICT"));
    }

    private String path(UUID order) { return "/api/v1/shipments/preparation/" + order + "/parcels"; }
}
