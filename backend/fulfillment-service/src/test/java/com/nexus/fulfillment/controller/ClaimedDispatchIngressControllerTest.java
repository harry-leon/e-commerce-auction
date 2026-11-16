package com.nexus.fulfillment.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.service.ClaimedDispatchIngressPolicy;
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

/** Bật worker thật qua property: user có quyền tạo hoặc Commerce đúng identity cũng không được đi vòng claim/preflight. */
@WebMvcTest(controllers = ShipmentController.class, properties = "app.shipping.dispatch-worker-enabled=true")
@Import({SecurityConfig.class, ShipmentDispatchExceptionHandler.class, ClaimedDispatchIngressPolicy.class})
@ActiveProfiles("test")
class ClaimedDispatchIngressControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private ShippingService shipping;
    @MockitoBean private WarehouseFulfillmentService warehouse;

    /** Không chấp nhận cả key mang tên dispatch-job do client tự khai; chặn trước service/carrier cho mọi quyền legacy. */
    @Test
    void legacyCreateCannotForgeAClaimedJob() throws Exception {
        String body = "{\"orderId\":\"" + UUID.randomUUID() + "\",\"carrier\":\"GHN\",\"serviceType\":\"STANDARD\","
                + "\"recipientName\":\"Fixture\",\"recipientPhone\":\"0900000000\",\"shippingAddress\":\"Fixture address\","
                + "\"weightGrams\":725,\"orderTotal\":50000,\"codAmount\":0}";
        for (String role : new String[]{"ROLE_ADMIN", "ROLE_SELLER", "ROLE_SUPPORT", "SHIPPING.CREATE_SHIPMENT"}) {
            mvc.perform(post("/api/v1/shipments").with(jwt().authorities(new SimpleGrantedAuthority(role)))
                    .header("Idempotency-Key", "dispatch-job:" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CLAIMED_DISPATCH_REQUIRED"));
        }
        verifyNoInteractions(shipping, warehouse);
    }

    /** Service token đúng identity/privilege chỉ vượt xác thực; OrderPaid thiếu kiện/claim vẫn bị đóng ở controlled mode. */
    @Test
    void validCommerceOrderPaidStillRequiresClaimedDispatch() throws Exception {
        mvc.perform(post("/api/v1/shipments/events/order-paid").with(jwt()
                .jwt(token -> token.subject(UUID.randomUUID().toString()).claim("azp", "nexus-commerce-service"))
                .authorities(new SimpleGrantedAuthority("SHIPPING.ORDER_PAID")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CLAIMED_DISPATCH_REQUIRED"));
        verifyNoInteractions(shipping, warehouse);
    }

    /** Cổng nghiệp vụ không mở quyền HTTP: khách/sai role vẫn nhận 401/403 trước dispatch. */
    @Test
    void preservesAuthenticationBoundary() throws Exception {
        mvc.perform(post("/api/v1/shipments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/shipments").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderId\":\"" + UUID.randomUUID() + "\","
                        + "\"recipientName\":\"Fixture\",\"recipientPhone\":\"0900000000\",\"shippingAddress\":\"Fixture address\","
                        + "\"weightGrams\":725,\"orderTotal\":50000,\"codAmount\":0}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(shipping, warehouse);
    }
}
