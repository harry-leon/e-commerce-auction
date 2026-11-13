package com.nexus.fulfillment.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ShippingService;
import com.nexus.fulfillment.service.WarehouseFulfillmentService;
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

/** Chặn giả mạo OrderPaid từ người dùng/service khác trước khi ShippingService có thể tạo vận đơn. */
@WebMvcTest(ShipmentController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ShipmentEventSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingService shipping;
    @MockitoBean private WarehouseFulfillmentService warehouse;

    /** Endpoint không phải callback public; JWT người dùng có role mạnh hoặc có privilege đơn lẻ vẫn không đủ. */
    @Test
    void deniesAnonymousAndUserTokens() throws Exception {
        mvc.perform(post("/api/v1/shipments/events/order-paid").contentType(MediaType.APPLICATION_JSON)
                .content(body(UUID.randomUUID()))).andExpect(status().isUnauthorized());
        for (String role : new String[] {"ROLE_BUYER", "ROLE_ADMIN", "SHIPPING.ORDER_PAID"}) {
            mvc.perform(post("/api/v1/shipments/events/order-paid").with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                    .authorities(new SimpleGrantedAuthority(role))).contentType(MediaType.APPLICATION_JSON)
                    .content(body(UUID.randomUUID()))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(shipping, warehouse);
    }

    /** Cần đồng thời azp và privilege: không dùng role kho để cấp thêm quyền shipping ngầm. */
    @Test
    void deniesWrongServiceOrMissingPrivilege() throws Exception {
        for (String client : new String[] {"nexus-auction-service", "nexus-commerce-service"}) {
            String privilege = client.equals("nexus-commerce-service") ? "INVENTORY.ORDER" : "SHIPPING.ORDER_PAID";
            mvc.perform(post("/api/v1/shipments/events/order-paid").with(jwt().jwt(j -> j.claim("azp", client))
                    .authorities(new SimpleGrantedAuthority(privilege))).contentType(MediaType.APPLICATION_JSON)
                    .content(body(UUID.randomUUID()))).andExpect(status().isForbidden());
        }
        verifyNoInteractions(shipping, warehouse);
    }

    /** Service token hợp lệ mới đi vào nghiệp vụ; test dùng mock shipping, không gọi carrier thật. */
    @Test
    void acceptsOnlyCommerceWithShippingPrivilege() throws Exception {
        UUID order = UUID.randomUUID();
        mvc.perform(post("/api/v1/shipments/events/order-paid")
                .with(jwt().jwt(j -> j.claim("azp", "nexus-commerce-service"))
                        .authorities(new SimpleGrantedAuthority("SHIPPING.ORDER_PAID")))
                .contentType(MediaType.APPLICATION_JSON).content(body(order))).andExpect(status().isOk());
        verify(shipping).createShipmentForPaidOrder(order);
    }

    /** Payload nhỏ nhất của contract hiện hành; chỉ orderId là bắt buộc. */
    private static String body(UUID order) { return "{\"orderId\":\"" + order + "\"}"; }
}
