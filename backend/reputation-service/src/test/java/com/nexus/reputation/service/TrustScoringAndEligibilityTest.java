package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.reputation.client.OrderVerificationClient;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.RecordTransactionRequest.TransactionOutcome;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.entity.Rating;
import com.nexus.reputation.entity.Reputation;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.event.ReputationScoreUpdatedEvent;
import com.nexus.reputation.event.TrustLevelChangedEvent;
import com.nexus.reputation.event.UserRatedEvent;
import com.nexus.reputation.repository.PenaltyRepository;
import com.nexus.reputation.repository.RatingRepository;
import com.nexus.reputation.repository.ReputationRepository;
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Kiểm thử tính điểm uy tín theo sao đánh giá, chuyển hạng TrustLevel, kiểm tra điều kiện đấu giá
 * và cập nhật bộ đếm giao dịch (HG-S3-2).
 */
@ExtendWith(MockitoExtension.class)
class TrustScoringAndEligibilityTest {

    @Mock
    private ReputationRepository reputationRepository;

    @Mock
    private PenaltyRepository penaltyRepository;

    @Mock
    private RatingRepository ratingRepository;

    @Mock
    private OrderVerificationClient orderVerificationClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReputationProperties properties;
    private ReputationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ReputationProperties();
        properties.setOrderVerificationEnabled(false);
        service = new ReputationServiceImpl(
                reputationRepository,
                penaltyRepository,
                ratingRepository,
                properties,
                orderVerificationClient,
                eventPublisher
        );
    }

    @Test
    void createRating_appliesScoreDeltaAndPromotesTrustLevelWithEvents() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Reputation sellerRep = new Reputation(sellerId, 108, TrustLevel.STANDARD);

        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(false);
        when(reputationRepository.findByUserIdForUpdate(sellerId)).thenReturn(Optional.of(sellerRep));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse saved = service.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 5, "Xuất sắc"));

        assertThat(saved.isAggregated()).isTrue();
        assertThat(sellerRep.getScore()).isEqualTo(111);
        assertThat(sellerRep.getTrustLevel()).isEqualTo(TrustLevel.HIGH);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, times(3)).publishEvent(eventCaptor.capture());
        List<Object> published = eventCaptor.getAllValues();
        assertThat(published).anySatisfy(e -> {
            assertThat(e).isInstanceOf(UserRatedEvent.class);
            UserRatedEvent rated = (UserRatedEvent) e;
            assertThat(rated.scoreDelta()).isEqualTo(3);
            assertThat(rated.newScore()).isEqualTo(111);
        });
        assertThat(published).anySatisfy(e -> assertThat(e).isInstanceOf(ReputationScoreUpdatedEvent.class));
        assertThat(published).anySatisfy(e -> {
            assertThat(e).isInstanceOf(TrustLevelChangedEvent.class);
            TrustLevelChangedEvent changed = (TrustLevelChangedEvent) e;
            assertThat(changed.previousLevel()).isEqualTo(TrustLevel.STANDARD);
            assertThat(changed.newLevel()).isEqualTo(TrustLevel.HIGH);
        });
    }

    @Test
    void createRating_lowStarDemotesScoreWithoutGoingBelowZero() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Reputation sellerRep = new Reputation(sellerId, 3, TrustLevel.LOW);

        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(false);
        when(reputationRepository.findByUserIdForUpdate(sellerId)).thenReturn(Optional.of(sellerRep));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createRating(buyerId, new CreateRatingRequest(orderId, sellerId, 1, "Kém"));

        assertThat(sellerRep.getScore()).isZero();
        assertThat(sellerRep.getTrustLevel()).isEqualTo(TrustLevel.LOW);
    }

    @Test
    void checkEligibility_enforcesBid40AndCreateAuction50Thresholds() {
        UUID userId = UUID.randomUUID();
        when(reputationRepository.findById(userId)).thenReturn(Optional.of(new Reputation(userId, 45, TrustLevel.LOW)));

        ReputationEligibilityResponse bidCheck = service.checkEligibility(userId, EligibilityAction.BID);
        ReputationEligibilityResponse sellCheck = service.checkEligibility(userId, EligibilityAction.CREATE_AUCTION);

        assertThat(bidCheck.eligible()).isTrue();
        assertThat(bidCheck.minRequiredScore()).isEqualTo(40);
        assertThat(sellCheck.eligible()).isFalse();
        assertThat(sellCheck.minRequiredScore()).isEqualTo(50);

        when(reputationRepository.findById(userId)).thenReturn(Optional.of(new Reputation(userId, 39, TrustLevel.LOW)));
        assertThat(service.checkEligibility(userId, EligibilityAction.BID).eligible()).isFalse();

        properties.setReputationEnforcementEnabled(false);
        assertThat(service.checkEligibility(userId, EligibilityAction.BID).eligible()).isTrue();
    }

    @Test
    void recordTransactionOutcome_keepsTotalTxConsistentWithSuccessAndFailed() {
        UUID userId = UUID.randomUUID();
        Reputation rep = new Reputation(userId, 100, TrustLevel.STANDARD);
        when(reputationRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rep));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ratingRepository.countByRateeIdAndDisputedFalse(userId)).thenReturn(2L);
        when(ratingRepository.findAverageScoreByRateeId(userId)).thenReturn(4.5d);

        ReputationSummaryResponse afterWin = service.recordTransactionOutcome(
                new RecordTransactionRequest(userId, TransactionOutcome.SUCCESS, true));
        assertThat(afterWin.totalTx()).isEqualTo(1);
        assertThat(afterWin.successTx()).isEqualTo(1);
        assertThat(afterWin.auctionWon()).isEqualTo(1);
        assertThat(afterWin.avgRating()).isEqualTo(4.5d);
        assertThat(afterWin.totalRatings()).isEqualTo(2L);
    }
}
