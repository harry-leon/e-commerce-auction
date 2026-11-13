package com.nexus.reputation.service;

import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.enums.EligibilityAction;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện nghiệp vụ điểm uy tín, đánh giá sau giao dịch, điều kiện đấu giá và xử phạt (SRS §3.5.3).
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
}
