package com.nexus.commerce.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.controller.internal.ShippingOrderInternalController;
import com.nexus.commerce.service.ShippingOrderSnapshotService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** PII giao hàng không được lộ cho browser/Admin/Auction hoặc service account thiếu privilege. */
@WebMvcTest(ShippingOrderInternalController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ShippingOrderInternalControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingOrderSnapshotService snapshots;

    /** Cần đồng thời đúng client và scope; role Admin không thay cho service identity. */
    @Test
    void requiresFulfillmentIdentityAndDedicatedPrivilege() throws Exception {
        UUID id = UUID.randomUUID();
        String path = "/api/v1/internal/commerce/shipping-orders/" + id;
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for (String client : new String[] {"nexus-web", "nexus-auction-service"}) {
            mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", client))
                    .authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_READ")))).andExpect(status().isForbidden());
        }
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isForbidden());
        verifyNoInteractions(snapshots);
        when(snapshots.get(id)).thenReturn(new ShippingOrderSnapshotService.Snapshot(id, "ORD-TEST", null,
                "Người nhận", "0900000000", "Địa chỉ test", "VNPAY", false, null, null));
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "nexus-fulfillment-service"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.SHIPPING_READ")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(id.toString()))
                .andExpect(jsonPath("$.data.recipientPhone").value("0900000000"))
                .andExpect(jsonPath("$.data.buyerEmail").doesNotExist());
    }
}
