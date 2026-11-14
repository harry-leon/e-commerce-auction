package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.controller.internal.ShippingDispatchClaimController;
import com.nexus.commerce.service.ShippingDispatchClaimService;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.time.Instant;
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

/** Claim mutation cần azp và privilege riêng, không cho Admin/Auction/SHIPPING_READ thay thế. */
@WebMvcTest(ShippingDispatchClaimController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class ShippingDispatchClaimControllerTest {
    private static final String PATH = "/api/v1/internal/commerce/shipping-dispatch-claims";
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingDispatchClaimService service;

    /** Sai client dù có cùng privilege, hoặc đúng client chỉ có READ, đều chặn trước service. */
    @Test
    void restrictsMutationToFulfillmentWithDispatchPrivilege() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        for (String client : new String[]{"web", "nexus-auction-service"}) {
            mvc.perform(post(PATH).with(jwt().jwt(j -> j.claim("azp", client)).authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_DISPATCH")))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        mvc.perform(post(PATH).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_READ"), new SimpleGrantedAuthority("ROLE_ADMIN")))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    /** Caller lấy subject JWT, created/replay giữ claimId; metadata lỗi trả 400/401 trước service. */
    @Test
    void requiresUuidMetadataAndReturnsStableClaim() throws Exception {
        UUID caller = UUID.randomUUID(); UUID key = UUID.randomUUID(); UUID handoff = UUID.randomUUID(); UUID parcel = UUID.randomUUID(); UUID id = UUID.randomUUID();
        var auth = jwt().jwt(j -> j.subject(caller.toString()).claim("azp", "nexus-fulfillment-service"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_DISPATCH"));
        String body = "{\"handoffId\":\"" + handoff + "\",\"parcelId\":\"" + parcel + "\"}";
        mvc.perform(post(PATH).with(auth).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        var claim = new ShippingDispatchClaimService.Claim(id, UUID.randomUUID(), handoff, parcel, Instant.now());
        when(service.claim(eq(caller), eq(key), any())).thenReturn(new ShippingDispatchClaimService.Result(claim, true), new ShippingDispatchClaimService.Result(claim, false));
        for (int expected : new int[]{201, 200}) {
            mvc.perform(post(PATH).with(auth).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().is(expected)).andExpect(jsonPath("$.data.id").value(id.toString()));
        }
        mvc.perform(post(PATH).with(jwt().jwt(j -> j.subject("bad").claim("azp", "nexus-fulfillment-service"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_DISPATCH"))).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
