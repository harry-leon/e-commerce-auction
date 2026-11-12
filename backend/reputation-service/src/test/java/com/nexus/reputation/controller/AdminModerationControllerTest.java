package com.nexus.reputation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.reputation.config.SecurityConfig;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm tra phân quyền RBAC cho các API gắn cờ khiếu nại và kiểm duyệt Admin/Support (HG-S3-3).
 */
@WebMvcTest({ReputationController.class, RatingController.class})
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class AdminModerationControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReputationService reputationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void adminQueriesAllowAdminAndSupportButRejectRegularUsers() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(reputationService.adminGetReputations(any(), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new ReputationSummaryResponse(userId, 110, TrustLevel.HIGH, 4, 4, 0, 1, 0, 5.0d, 2L)),
                        PageRequest.of(0, 20), 1));
        when(reputationService.adminGetRatings(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        when(reputationService.adminGetPenalties(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/v1/reputations/admin/users"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/reputations/admin/users")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/reputations/admin/users")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].trustLevel").value("HIGH"));

        mvc.perform(get("/api/v1/reputations/admin/ratings")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/reputations/admin/penalties")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT"))))
                .andExpect(status().isOk());
    }

    @Test
    void manualAdjustmentAndDisputeResolutionRequireAdminRole() throws Exception {
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();
        UUID ratingId = UUID.randomUUID();

        String adjustBody = """
                {"userId":"%s","scoreDelta":10,"reason":"Khôi phục sau xác minh"}
                """.formatted(targetUserId);

        when(reputationService.adminAdjustReputation(eq(adminId), any())).thenReturn(new PenaltyResponse(
                UUID.randomUUID(), targetUserId, PenaltyRefType.MANUAL, UUID.randomUUID(),
                10, 90, 100, TrustLevel.STANDARD, "[ADMIN] Khôi phục sau xác minh", false, Instant.now()));

        // ROLE_SUPPORT chỉ có quyền tra cứu, không được sửa điểm thủ công.
        mvc.perform(post("/api/v1/reputations/admin/adjustments")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/reputations/admin/adjustments")
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(adjustBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refType").value("MANUAL"))
                .andExpect(jsonPath("$.data.newScore").value(100));

        String resolveBody = """
                {"resolution":"INVALIDATE","reason":"Đánh giá sai sự thật"}
                """;
        when(reputationService.resolveRatingDispute(eq(ratingId), eq(adminId), any()))
                .thenReturn(new RatingResponse(
                        ratingId, UUID.randomUUID(), UUID.randomUUID(), targetUserId,
                        1, "Sai", true, true, Instant.now()));

        mvc.perform(post("/api/v1/reputations/admin/ratings/{ratingId}/dispute/resolve", ratingId)
                        .with(jwt().jwt(j -> j.subject(adminId.toString()))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDisputed").value(true));

        when(reputationService.flagRatingDispute(eq(ratingId), eq(targetUserId), anyBoolean(), any()))
                .thenReturn(new RatingResponse(
                        ratingId, UUID.randomUUID(), UUID.randomUUID(), targetUserId,
                        1, "Sai", true, true, Instant.now()));

        mvc.perform(post("/api/v1/ratings/{ratingId}/dispute", ratingId)
                        .with(jwt().jwt(j -> j.subject(targetUserId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Khiếu nại đánh giá sai\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isDisputed").value(true));
    }
}
