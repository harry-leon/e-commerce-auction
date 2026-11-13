package com.nexus.reputation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.reputation.config.SecurityConfig;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.service.ReputationService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra endpoint điều kiện uy tín công khai và nội bộ (HG-S3-2). */
@WebMvcTest({ReputationController.class, ReputationInternalController.class})
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class EligibilityControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReputationService reputationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void publicAndInternalEligibilitySecurityContracts() throws Exception {
        UUID userId = UUID.randomUUID();
        when(reputationService.checkEligibility(userId, EligibilityAction.CREATE_AUCTION))
                .thenReturn(new ReputationEligibilityResponse(
                        userId, EligibilityAction.CREATE_AUCTION, false, 45, 50, TrustLevel.LOW, "Không đủ điểm"));

        mvc.perform(get("/api/v1/reputations/{userId}/eligibility", userId)
                        .param("action", "CREATE_AUCTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.minRequiredScore").value(50));

        mvc.perform(get("/api/v1/internal/reputations/{userId}/eligibility", userId)
                        .param("action", "CREATE_AUCTION"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/internal/reputations/{userId}/eligibility", userId)
                        .param("action", "CREATE_AUCTION")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/internal/reputations/{userId}/eligibility", userId)
                        .param("action", "CREATE_AUCTION")
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-auction-service"))
                                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false));
    }

    @Test
    void internalTransactionsEndpointAllowsCommerceServiceIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        when(reputationService.recordTransactionOutcome(any())).thenReturn(
                new ReputationSummaryResponse(userId, 100, TrustLevel.STANDARD, 1, 1, 0, 1, 0, 0.0d, 0L));

        String body = """
                {"userId":"%s","outcome":"SUCCESS","auction":true}
                """.formatted(userId);

        mvc.perform(post("/api/v1/internal/reputations/transactions")
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-commerce-service"))
                                .authorities(new SimpleGrantedAuthority("REPUTATION.PENALTY")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTx").value(1))
                .andExpect(jsonPath("$.data.auctionWon").value(1));
    }
}
