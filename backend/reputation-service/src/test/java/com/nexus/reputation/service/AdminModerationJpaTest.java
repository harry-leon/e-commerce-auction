package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.AdminAdjustReputationRequest;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.DisputeRatingRequest;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.dto.ResolveDisputeRequest;
import com.nexus.reputation.dto.ResolveDisputeRequest.DisputeResolution;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Kiểm thử end-to-end tầng JPA cho luồng khiếu nại đánh giá, phán quyết Admin và điều chỉnh điểm thủ công.
 */
@DataJpaTest(properties = "app.reputation.order-verification-enabled=false")
@ActiveProfiles("test")
@Import({ReputationServiceImpl.class, AdminModerationJpaTest.Config.class})
class AdminModerationJpaTest {

    @Autowired
    private ReputationService reputationService;

    @TestConfiguration
    static class Config {
        @Bean
        ReputationProperties reputationProperties() {
            return new ReputationProperties();
        }
    }

    @Test
    void fullDisputeAndManualAdjustmentFlow_persistsAndReversesScoreIdempotently() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        RatingResponse badRating = reputationService.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 1, "Đánh giá 1 sao"));
        assertThat(reputationService.getReputation(sellerId).score()).isEqualTo(95);

        reputationService.flagRatingDispute(
                badRating.id(), sellerId, false, new DisputeRatingRequest("Có biên bản giao đủ"));
        assertThat(reputationService.getRatingsForUser(sellerId, PageRequest.of(0, 10)).getTotalElements()).isZero();
        assertThat(reputationService.adminGetRatings(true, sellerId, PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);

        reputationService.resolveRatingDispute(
                badRating.id(), adminId, new ResolveDisputeRequest(DisputeResolution.INVALIDATE, "Hủy 1 sao sai"));
        reputationService.resolveRatingDispute(
                badRating.id(), adminId, new ResolveDisputeRequest(DisputeResolution.INVALIDATE, "Gửi lặp"));

        ReputationSummaryResponse afterResolve = reputationService.getReputation(sellerId);
        assertThat(afterResolve.score()).isEqualTo(100);

        UUID adjustmentRef = UUID.randomUUID();
        reputationService.adminAdjustReputation(
                adminId, new AdminAdjustReputationRequest(sellerId, 12, "Thưởng uy tín", adjustmentRef));
        assertThat(reputationService.getReputation(sellerId).score()).isEqualTo(112);
        assertThat(reputationService.adminGetPenalties(sellerId, PenaltyRefType.MANUAL, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }
}
