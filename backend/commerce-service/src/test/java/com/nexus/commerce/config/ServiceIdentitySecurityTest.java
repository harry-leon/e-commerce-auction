package com.nexus.commerce.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Namespace Commerce nội bộ chỉ nhận identity Auction; probe là fixture, không phải API tạo order thật. */
@WebMvcTest(ServiceIdentitySecurityTest.Probe.class)
@Import({SecurityConfig.class, ServiceIdentitySecurityTest.Probe.class})
@ActiveProfiles("test")
class ServiceIdentitySecurityTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private JwtDecoder decoder;

    @Test
    void requiresBothAuctionClientAndPrivilege() throws Exception {
        String path = "/api/v1/internal/commerce/probe";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION")))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "nexus-auction-service"))
                .authorities(new SimpleGrantedAuthority("INVENTORY.AUCTION")))).andExpect(status().isForbidden());
        mvc.perform(get(path).with(jwt().jwt(j -> j.claim("azp", "nexus-auction-service"))
                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION")))).andExpect(status().isOk());
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/internal/commerce/probe")
        String probe() { return "ok"; }
    }
}
