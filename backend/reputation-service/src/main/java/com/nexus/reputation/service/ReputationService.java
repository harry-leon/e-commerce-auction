package com.nexus.reputation.service;

import com.nexus.reputation.dto.AdminAdjustReputationRequest;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.DisputeRatingRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.dto.ResolveDisputeRequest;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện nghiệp vụ điểm uy tín, đánh giá, điều kiện đấu giá và kiểm duyệt Admin (SRS §3.5.3).
 */
public interface ReputationService {

    ReputationSummaryResponse getReputation(UUID userId);

    Page<PenaltyResponse> getPenalties(UUID userId, Pageable pageable);

    PenaltyResponse applyPenalty(ApplyPenaltyRequest request);

    RatingResponse createRating(UUID raterId, CreateRatingRequest request);

    Page<RatingResponse> getRatingsForUser(UUID userId, Pageable pageable);

    List<RatingResponse> getRatingsForOrder(UUID orderId);

    ReputationEligibilityResponse checkEligibility(UUID userId, EligibilityAction action);

    ReputationSummaryResponse recordTransactionOutcome(RecordTransactionRequest request);

    RatingResponse flagRatingDispute(UUID ratingId, UUID actorId, boolean isPrivileged, DisputeRatingRequest request);

    RatingResponse resolveRatingDispute(UUID ratingId, UUID adminId, ResolveDisputeRequest request);

    PenaltyResponse adminAdjustReputation(UUID adminId, AdminAdjustReputationRequest request);

    Page<ReputationSummaryResponse> adminGetReputations(TrustLevel trustLevel, Pageable pageable);

    Page<RatingResponse> adminGetRatings(Boolean disputedOnly, UUID rateeId, Pageable pageable);

    Page<PenaltyResponse> adminGetPenalties(UUID userId, PenaltyRefType refType, Pageable pageable);
}
