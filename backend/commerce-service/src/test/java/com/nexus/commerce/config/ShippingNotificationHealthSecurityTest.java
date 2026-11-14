package com.nexus.commerce.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Actuator thật trên schema PostgreSQL riêng: public không thấy metadata, Admin xem health mà không probe Notification. */
@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "spring.config.import=", "eureka.client.enabled=false",
        "spring.jpa.hibernate.ddl-auto=validate", "app.shipping.notification-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class ShippingNotificationHealthSecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;
    private static final String SCHEMA = "shipping_health_" + UUID.randomUUID().toString().replace("-", "");

    /** Flyway tạo schema UUID trống, không để scheduler đầy đủ của app chạm fixture các test Payment khác. */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("NEXUS_TEST_DB_URL");
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("spring.datasource.username", () -> "postgres"); registry.add("spring.datasource.password", () -> "");
        registry.add("spring.flyway.schemas", () -> SCHEMA); registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    /** Không công khai backlog hoặc cấu trúc health cho Buyer; Admin thấy enabled=false và connectivity chưa xác minh. */
    @Test
    void exposesTransportDetailsOnlyToAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(get("/actuator/health").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/shippingNotificationTransport").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.details.enabled").value(false))
                .andExpect(jsonPath("$.details.connectivityVerified").value(false));
    }
}
