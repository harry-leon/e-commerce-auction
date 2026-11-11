package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Kiểm thử tầng JPA trên H2 (MODE=PostgreSQL): xác nhận khóa duy nhất uq_user_penalty_ref,
 * bộ đếm auction_failed và giới hạn điểm không âm khi áp dụng phạt 10 điểm.
 */
@DataJpaTest
@ActiveProfiles("test")
@Import({ReputationServiceImpl.class, ReputationServiceJpaTest.Config.class})
class ReputationServiceJpaTest {

    @Autowired
    private ReputationService reputationService;

    @TestConfiguration
    static class Config {
        @Bean
        ReputationProperties reputationProperties() {
            return new ReputationProperties();
        }
    }

    /** Áp dụng phạt hai lần cho cùng (userId, AUCTION, auctionId) chỉ trừ đúng 10 điểm một lần. */
    @Test
    void applyPenalty_isIdempotentAndPersistsPenaltyRecord() {
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        ApplyPenaltyRequest request = new ApplyPenaltyRequest(
                userId, PenaltyRefType.AUCTION, auctionId, null, "Không thanh toán trong hạn 24h");

        PenaltyResponse first = reputationService.applyPenalty(request);
        PenaltyResponse second = reputationService.applyPenalty(request);
        ReputationSummaryResponse summary = reputationService.getReputation(userId);

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(first.scoreDelta()).isEqualTo(-10);
        assertThat(first.newScore()).isEqualTo(90);
        assertThat(second.idempotentReplay()).isTrue();
        assertThat(second.penaltyId()).isEqualTo(first.penaltyId());
        assertThat(summary.score()).isEqualTo(90);
        assertThat(summary.failedTx()).isEqualTo(1);
        assertThat(summary.auctionFailed()).isEqualTo(1);
        assertThat(summary.trustLevel()).isEqualTo(TrustLevel.STANDARD);
    }

    /** Nhiều vi phạm khác phiên đấu giá cộng dồn hạ hạng LOW và khóa sàn không âm. */
    @Test
    void multiplePenaltiesDowngradeTrustLevelAndRespectNonNegativeFloor() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            reputationService.applyPenalty(new ApplyPenaltyRequest(
                    userId, PenaltyRefType.AUCTION, UUID.randomUUID(), null, "Timeout #" + i));
        }
        ReputationSummaryResponse afterFive = reputationService.getReputation(userId);
        assertThat(afterFive.score()).isEqualTo(50);
        assertThat(afterFive.trustLevel()).isEqualTo(TrustLevel.LOW);

        PenaltyResponse heavy = reputationService.applyPenalty(new ApplyPenaltyRequest(
                userId, PenaltyRefType.MANUAL, UUID.randomUUID(), 80, "Vi phạm nghiêm trọng"));
        assertThat(heavy.newScore()).isZero();
        assertThat(reputationService.getReputation(userId).score()).isZero();
    }
}
