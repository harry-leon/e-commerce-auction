package com.nexus.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.catalog.config.SecurityConfig;
import com.nexus.catalog.dto.request.PricingRuleRequest;
import com.nexus.catalog.dto.response.PricePreviewResponse;
import com.nexus.catalog.dto.response.PricingRuleResponse;
import com.nexus.catalog.service.PricingRuleService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra phân quyền và validation các endpoint /api/v1/pricing-rules. */
@WebMvcTest(PricingRuleController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class PricingRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PricingRuleService pricingRuleService;

    private JwtRequestPostProcessor withRole(String role) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(role));
    }

    @Test
    void listActiveAndPreviewArePublic() throws Exception {
        UUID ruleId = UUID.randomUUID();
        when(pricingRuleService.listActive()).thenReturn(List.of(new PricingRuleResponse(
                ruleId, "Default +15%", null, null,
                new BigDecimal("0.15"), BigDecimal.ZERO, BigDecimal.ZERO,
                0, null, null, true, Instant.now(), Instant.now())));
        when(pricingRuleService.preview(new BigDecimal("100000"), null)).thenReturn(new PricePreviewResponse(
                new BigDecimal("100000"), new BigDecimal("115000.00"),
                ruleId, "Default +15%", new BigDecimal("0.15"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.15")));

        mockMvc.perform(get("/api/v1/pricing-rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("Default +15%"));

        mockMvc.perform(get("/api/v1/pricing-rules/preview").param("originalPrice", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sellingPrice").value(115000.00));
    }

    @Test
    void adminCrudRequiresAdminRole() throws Exception {
        PricingRuleRequest request = new PricingRuleRequest(
                "Tet Peak", null, new BigDecimal("0.20"), new BigDecimal("0.05"),
                new BigDecimal("0.02"), 10, null, null, true);
        UUID id = UUID.randomUUID();
        PricingRuleResponse response = new PricingRuleResponse(
                id, "Tet Peak", null, null, new BigDecimal("0.20"), new BigDecimal("0.05"),
                new BigDecimal("0.02"), 10, null, null, true, Instant.now(), Instant.now());

        mockMvc.perform(post("/api/v1/pricing-rules")
                        .with(withRole("ROLE_SELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        when(pricingRuleService.create(any(PricingRuleRequest.class))).thenReturn(response);
        mockMvc.perform(post("/api/v1/pricing-rules")
                        .with(withRole("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Tet Peak"));

        when(pricingRuleService.update(eq(id), any(PricingRuleRequest.class))).thenReturn(response);
        mockMvc.perform(put("/api/v1/pricing-rules/{id}", id)
                        .with(withRole("ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        doNothing().when(pricingRuleService).delete(id);
        mockMvc.perform(delete("/api/v1/pricing-rules/{id}", id)
                        .with(withRole("ROLE_ADMIN")))
                .andExpect(status().isNoContent());
    }
}
