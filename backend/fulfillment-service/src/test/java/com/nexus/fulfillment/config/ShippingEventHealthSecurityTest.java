package com.nexus.fulfillment.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Health root cho probe công khai, chỉ Admin được xem component/chi tiết transport; không xin token hoặc gọi Commerce. */
@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false", "app.shipping.commerce.events-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShippingEventHealthSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;

    /** Anonymous/Buyer không thấy metadata, Admin thấy worker tắt và không bị hiểu UP là downstream đã được probe. */
    @Test
    void exposesTransportDetailsOnlyToAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/shippingEventTransport").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.details.enabled").value(false))
                .andExpect(jsonPath("$.details.connectivityVerified").value(false));
    }
}
