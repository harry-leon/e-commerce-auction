package com.nexus.commerce.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.controller.internal.ShippingEventInternalController;
import com.nexus.commerce.service.ShippingEventInboxService;
import com.nexus.common.exception.GlobalExceptionHandler;
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

/** Carrier/browser/Admin không được ghi trực tiếp Order: cần đúng Fulfillment và privilege riêng. */
@WebMvcTest(ShippingEventInternalController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ShippingEventInternalControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingEventInboxService inbox;

    /** Kiểm tra cả client/scope trước service và validate payload trước khi ACK. */
    @Test
    void requiresDedicatedServiceIdentityAndValidPayload() throws Exception {
        String path = "/api/v1/internal/commerce/shipping-events";
        String body = "{\"eventId\":\"%s\",\"eventType\":\"ShipmentPickedUp\",\"occurredAt\":\"2026-11-13T00:00:00Z\","
                + "\"shipmentId\":\"%s\",\"orderId\":\"%s\",\"carrier\":\"GHN\",\"trackingNumber\":\"TRACK\",\"status\":\"PICKED_UP\"}";
        body = body.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        for (String client : new String[] {"nexus-web", "nexus-auction-service"}) {
            mvc.perform(post(path).with(jwt().jwt(j -> j.claim("azp", client)).authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_EVENTS")))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        }
        mvc.perform(post(path).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(inbox);
        mvc.perform(post(path).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service")).authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_EVENTS")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNoContent());
        mvc.perform(post(path).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service")).authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_EVENTS")))
                .contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnprocessableEntity());
    }
}
