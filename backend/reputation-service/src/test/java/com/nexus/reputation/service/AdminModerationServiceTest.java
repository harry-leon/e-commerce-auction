package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.client.OrderVerificationClient;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.AdminAdjustReputationRequest;
import com.nexus.reputation.dto.DisputeRatingRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.dto.ResolveDisputeRequest;
import com.nexus.reputation.dto.ResolveDisputeRequest.DisputeResolution;
import com.nexus.reputation.entity.Penalty;
import com.nexus.reputation.entity.Rating;
import com.nexus.reputation.entity.Reputation;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.event.ReputationScoreUpdatedEvent;
import com.nexus.reputation.repository.PenaltyRepository;
import com.nexus.reputation.repository.RatingRepository;
import com.nexus.reputation.repository.ReputationRepository;
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Kiểm thử đơn vị cho nghiệp vụ khiếu nại đánh giá, phán quyết Admin và điều chỉnh điểm thủ công (HG-S3-3).
 */
@ExtendWith(MockitoExtension.class)
class AdminModerationServiceTest {

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

    private ReputationServiceImpl service;

    @BeforeEach
    void setUp() {
        ReputationProperties properties = new ReputationProperties();
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
    void flagRatingDispute_allowsRateeOrPrivilegedAndBlocksOutsider() {
        UUID ratingId = UUID.randomUUID();
        UUID raterId = UUID.randomUUID();
        UUID rateeId = UUID.randomUUID();
        UUID strangerId = UUID.randomUUID();
        Rating rating = new Rating(
                ratingId, raterId, rateeId, UUID.randomUUID(), 1, "Sai sự thật", true, false, Instant.now());

        when(ratingRepository.findById(ratingId)).thenReturn(Optional.of(rating));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.flagRatingDispute(
                ratingId, strangerId, false, new DisputeRatingRequest("Gắn bừa")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_OWNER.name());

        RatingResponse flagged = service.flagRatingDispute(
                ratingId, rateeId, false, new DisputeRatingRequest("Có bằng chứng giao đủ hàng"));
        assertThat(flagged.isDisputed()).isTrue();

        RatingResponse idempotent = service.flagRatingDispute(ratingId, strangerId, true, null);
        assertThat(idempotent.isDisputed()).isTrue();
    }

    @Test
    void resolveRatingDispute_invalidateReversesScoreOnceAndDismissClearsFlag() {
        UUID ratingId = UUID.randomUUID();
        UUID rateeId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Rating rating = new Rating(
                ratingId, UUID.randomUUID(), rateeId, UUID.randomUUID(), 1, "Spam 1 sao", true, true, Instant.now());
        Reputation rep = new Reputation(rateeId, 95, TrustLevel.STANDARD);

        when(ratingRepository.findById(ratingId)).thenReturn(Optional.of(rating));
        when(reputationRepository.findByUserIdForUpdate(rateeId)).thenReturn(Optional.of(rep));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(rateeId, PenaltyRefType.RATING, ratingId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Penalty(
                        UUID.randomUUID(), rateeId, "Hoàn tác", 5, PenaltyRefType.RATING, ratingId, Instant.now())));
        when(penaltyRepository.saveAndFlush(any(Penalty.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse invalidated = service.resolveRatingDispute(
                ratingId, adminId, new ResolveDisputeRequest(DisputeResolution.INVALIDATE, "Xác minh đơn giao đúng"));
        assertThat(invalidated.isDisputed()).isTrue();
        assertThat(rep.getScore()).isEqualTo(100);
        verify(eventPublisher, times(1)).publishEvent(any(ReputationScoreUpdatedEvent.class));

        // Gọi INVALIDATE lần 2 phải idempotent, không cộng điểm lần hai.
        service.resolveRatingDispute(
                ratingId, adminId, new ResolveDisputeRequest(DisputeResolution.INVALIDATE, "Lặp lại"));
        assertThat(rep.getScore()).isEqualTo(100);

        RatingResponse dismissed = service.resolveRatingDispute(
                ratingId, adminId, new ResolveDisputeRequest(DisputeResolution.DISMISS, "Khôi phục để test dismiss"));
        assertThat(dismissed.isDisputed()).isFalse();
    }

    @Test
    void adminAdjustReputation_validatesDeltaAndReplaysByRefId() {
        UUID adminId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        Reputation rep = new Reputation(userId, 100, TrustLevel.STANDARD);

        assertThatThrownBy(() -> service.adminAdjustReputation(
                adminId, new AdminAdjustReputationRequest(userId, 0, "Không hợp lệ", refId)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name());

        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(userId, PenaltyRefType.MANUAL, refId))
                .thenReturn(Optional.empty(), Optional.empty());
        when(reputationRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rep));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.saveAndFlush(any(Penalty.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyResponse adjusted = service.adminAdjustReputation(
                adminId, new AdminAdjustReputationRequest(userId, 15, "Khôi phục sau đối soát", refId));
        assertThat(adjusted.newScore()).isEqualTo(115);
        assertThat(adjusted.trustLevel()).isEqualTo(TrustLevel.HIGH);
        assertThat(adjusted.reason()).contains("[ADMIN " + adminId + "]");
    }

    @Test
    void adminLookups_supportFilterCombinations() {
        UUID userId = UUID.randomUUID();
        PageRequest pageReq = PageRequest.of(0, 10);
        when(reputationRepository.findByTrustLevelOrderByScoreDesc(TrustLevel.VIP, pageReq))
                .thenReturn(new PageImpl<>(List.of(new Reputation(userId, 160, TrustLevel.VIP))));
        when(ratingRepository.findByDisputedTrueOrderByCreatedAtDesc(pageReq))
                .thenReturn(new PageImpl<>(List.of()));
        when(penaltyRepository.findByUserIdAndRefTypeOrderByCreatedAtDesc(userId, PenaltyRefType.MANUAL, pageReq))
                .thenReturn(new PageImpl<>(List.of()));

        Page<ReputationSummaryResponse> reps = service.adminGetReputations(TrustLevel.VIP, pageReq);
        assertThat(reps.getTotalElements()).isEqualTo(1);
        assertThat(service.adminGetRatings(true, null, pageReq).getTotalElements()).isZero();
        assertThat(service.adminGetPenalties(userId, PenaltyRefType.MANUAL, pageReq).getTotalElements()).isZero();
    }
}
