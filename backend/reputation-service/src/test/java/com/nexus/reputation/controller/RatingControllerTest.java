package com.nexus.reputation.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.reputation.config.SecurityConfig;
import com.nexus.reputation.dto.RatingResponse;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra phân quyền, validation và Envelope JSON cho RatingController (HG-S3-1). */
@WebMvcTest(RatingController.class)
@Import({SecurityConfig.class, com.nexus.common.exception.GlobalExceptionHandler.class})
class RatingControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReputationService reputationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void createRatingRequiresAuthenticationAndValidPayload() throws Exception {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        String validBody = """
                {"orderId":"%s","rateeId":"%s","score":5,"comment":"Đóng gói kỹ"}
                """.formatted(orderId, sellerId);

        mvc.perform(post("/api/v1/ratings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isUnauthorized());

        String invalidScoreBody = """
                {"orderId":"%s","rateeId":"%s","score":6,"comment":"Quá phạm vi"}
                """.formatted(orderId, sellerId);

        mvc.perform(post("/api/v1/ratings")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidScoreBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false));

        RatingResponse expected = new RatingResponse(
                UUID.randomUUID(), orderId, buyerId, sellerId, 5, "Đóng gói kỹ", false, false, Instant.now());
        when(reputationService.createRating(eq(buyerId), any())).thenReturn(expected);

        mvc.perform(post("/api/v1/ratings")
                        .with(jwt().jwt(j -> j.subject(buyerId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.raterId").value(buyerId.toString()))
                .andExpect(jsonPath("$.data.score").value(5));
    }

    @Test
    void publicUserRatingsAndAuthenticatedOrderRatings() throws Exception {
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        RatingResponse item = new RatingResponse(
                UUID.randomUUID(), orderId, buyerId, sellerId, 4, "Ổn áp", false, false, Instant.now());

        when(reputationService.getRatingsForUser(eq(sellerId), any()))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));
        when(reputationService.getRatingsForOrder(orderId))
                .thenReturn(List.of(item));

        mvc.perform(get("/api/v1/ratings/user/" + sellerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].score").value(4))
                .andExpect(jsonPath("$.meta.pagination.totalElements").value(1));

        mvc.perform(get("/api/v1/ratings/order/" + orderId))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/ratings/order/" + orderId)
                        .with(jwt().jwt(j -> j.subject(buyerId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderId").value(orderId.toString()));
    }
}
