package com.nexus.reputation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.reputation.config.SecurityConfig;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.service.ReputationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra phân quyền REST và ServiceIdentity của ReputationController và ReputationInternalController. */
@WebMvcTest({ReputationController.class, ReputationInternalController.class})
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class ReputationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReputationService reputationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    /** Chỉ nexus-commerce-service có REPUTATION.PENALTY mới gọi được endpoint trừ điểm nội bộ. */
    @Test
    void internalPenaltyEndpointRequiresCommerceServiceIdentity() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        String body = """
                {"userId":"%s","refType":"AUCTION","refId":"%s","penaltyPoints":10,"reason":"Quá hạn thanh toán 24h"}
                """.formatted(userId, auctionId);

        when(reputationService.applyPenalty(any())).thenReturn(new PenaltyResponse(
                UUID.randomUUID(), userId, PenaltyRefType.AUCTION, auctionId,
                -10, 100, 90, TrustLevel.STANDARD, "Quá hạn thanh toán 24h", false, Instant.now()));

        mvc.perform(post("/api/v1/internal/reputations/penalties")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/v1/internal/reputations/penalties")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/internal/reputations/penalties")
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                                .authorities(new SimpleGrantedAuthority("REPUTATION.PENALTY")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/internal/reputations/penalties")
                        .with(jwt().jwt(j -> j.claim("azp", "nexus-commerce-service"))
                                .authorities(new SimpleGrantedAuthority("REPUTATION.PENALTY")))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scoreDelta").value(-10))
                .andExpect(jsonPath("$.data.newScore").value(90));
    }

    /** Lịch sử phạt chỉ cho chính chủ hoặc ADMIN/SUPPORT; tóm tắt công khai đọc được qua userId. */
    @Test
    void scopesPenaltiesToOwnerOrAdminAndAllowsPublicSummary() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();

        when(reputationService.getReputation(owner)).thenReturn(
                new ReputationSummaryResponse(owner, 90, TrustLevel.STANDARD, 1, 0, 1, 1, 1));
        when(reputationService.getPenalties(any(), any())).thenReturn(new PageImpl<>(List.of(new PenaltyResponse(
                UUID.randomUUID(), owner, PenaltyRefType.AUCTION, UUID.randomUUID(),
                -10, 100, 90, TrustLevel.STANDARD, "Quá hạn", false, Instant.now()))));

        mvc.perform(get("/api/v1/reputations/{userId}", owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(90));

        mvc.perform(get("/api/v1/reputations/me").with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(90));

        mvc.perform(get("/api/v1/reputations/{userId}/penalties", owner)
                        .with(jwt().jwt(j -> j.subject(stranger.toString()))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/reputations/{userId}/penalties", owner)
                        .with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].scoreDelta").value(-10));

        mvc.perform(get("/api/v1/reputations/{userId}/penalties", owner)
                        .with(jwt().jwt(j -> j.subject(stranger.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }
}
