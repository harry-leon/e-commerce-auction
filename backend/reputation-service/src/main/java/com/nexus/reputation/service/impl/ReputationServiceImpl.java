package com.nexus.reputation.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.client.OrderVerificationClient;
import com.nexus.reputation.client.OrderVerificationClient.VerifiedOrder;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.AdminAdjustReputationRequest;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.DisputeRatingRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.RecordTransactionRequest.TransactionOutcome;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.dto.ResolveDisputeRequest;
import com.nexus.reputation.dto.ResolveDisputeRequest.DisputeResolution;
import com.nexus.reputation.entity.Penalty;
import com.nexus.reputation.entity.Rating;
import com.nexus.reputation.entity.Reputation;
import com.nexus.reputation.enums.EligibilityAction;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.event.ReputationPenaltyAppliedEvent;
import com.nexus.reputation.event.ReputationScoreUpdatedEvent;
import com.nexus.reputation.event.TrustLevelChangedEvent;
import com.nexus.reputation.event.UserRatedEvent;
import com.nexus.reputation.repository.PenaltyRepository;
import com.nexus.reputation.repository.RatingRepository;
import com.nexus.reputation.repository.ReputationRepository;
import com.nexus.reputation.service.ReputationService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt nghiệp vụ điểm uy tín, đánh giá hai chiều, tính TrustLevel, điều kiện đấu giá và kiểm duyệt Admin (SRS §3.5.3).
 *
 * Quy tắc chính:
 * - Đánh giá: 5->+3, 4->+1, 3->0, 2->-3, 1->-5 điểm; cấm tự đánh giá, tối đa 1 lần mỗi bên/đơn.
 * - Điều kiện đấu giá: BID >= 40, CREATE_AUCTION >= 50 (khi bật FEATURE_REPUTATION_ENFORCEMENT).
 * - Khiếu nại & Admin: chỉ rateeId hoặc ADMIN/SUPPORT được gắn cờ; ADMIN xử lý DISMISS/INVALIDATE idempotent.
 */
@Service
public class ReputationServiceImpl implements ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationServiceImpl.class);
    private static final Set<String> RATABLE_ORDER_STATUSES = Set.of("DELIVERED", "COMPLETED");

    private final ReputationRepository reputationRepository;
    private final PenaltyRepository penaltyRepository;
    private final RatingRepository ratingRepository;
    private final ReputationProperties properties;
    private final OrderVerificationClient orderVerificationClient;
    private final ApplicationEventPublisher eventPublisher;

    public ReputationServiceImpl(
            ReputationRepository reputationRepository,
            PenaltyRepository penaltyRepository,
            ReputationProperties properties,
            ApplicationEventPublisher eventPublisher) {
        this(reputationRepository, penaltyRepository, null, properties, null, eventPublisher);
    }

    @Autowired
    public ReputationServiceImpl(
            ReputationRepository reputationRepository,
            PenaltyRepository penaltyRepository,
            @Autowired(required = false) RatingRepository ratingRepository,
            ReputationProperties properties,
            @Autowired(required = false) OrderVerificationClient orderVerificationClient,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.reputationRepository = reputationRepository;
        this.penaltyRepository = penaltyRepository;
        this.ratingRepository = ratingRepository;
        this.properties = properties;
        this.orderVerificationClient = orderVerificationClient;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public ReputationSummaryResponse getReputation(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người dùng không được để trống");
        }
        Reputation rep = reputationRepository.findById(userId)
                .orElseGet(() -> new Reputation(userId, properties.getInitialScore(), TrustLevel.STANDARD));
        return toSummaryResponse(rep);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PenaltyResponse> getPenalties(UUID userId, Pageable pageable) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người dùng không được để trống");
        }
        Reputation rep = reputationRepository.findById(userId).orElse(null);
        int currentScore = rep != null ? rep.getScore() : properties.getInitialScore();
        TrustLevel currentLevel = rep != null ? rep.getTrustLevel() : TrustLevel.STANDARD;
        return penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(p -> toPenaltyResponse(p, currentScore, currentScore, currentLevel, false));
    }

    @Override
    @Transactional
    public PenaltyResponse applyPenalty(ApplyPenaltyRequest request) {
        if (request == null || request.userId() == null || request.refType() == null || request.refId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin xử phạt không hợp lệ");
        }

        Optional<Penalty> existingFast = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                request.userId(), request.refType(), request.refId());
        if (existingFast.isPresent()) {
            Reputation current = reputationRepository.findById(request.userId())
                    .orElseGet(() -> new Reputation(request.userId(), properties.getInitialScore(), TrustLevel.STANDARD));
            return replayResponse(existingFast.get(), current);
        }

        Reputation rep = lockOrCreateReputation(request.userId());

        Optional<Penalty> existingLocked = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                request.userId(), request.refType(), request.refId());
        if (existingLocked.isPresent()) {
            return replayResponse(existingLocked.get(), rep);
        }

        int points = resolvePenaltyPoints(request);
        int previousScore = rep.getScore();
        TrustLevel previousLevel = rep.getTrustLevel();
        int newScore = Math.max(0, previousScore - points);
        TrustLevel newLevel = resolveTrustLevel(newScore);
        int appliedDelta = newScore - previousScore;
        int recordedDelta = -points;

        rep.setScore(newScore);
        rep.setTrustLevel(newLevel);
        rep.setTotalTx(rep.getTotalTx() + 1);
        rep.setFailedTx(rep.getFailedTx() + 1);
        if (request.refType() == PenaltyRefType.AUCTION) {
            rep.setAuctionFailed(rep.getAuctionFailed() + 1);
        }
        reputationRepository.saveAndFlush(rep);

        String normalizedReason = normalizeReason(request.reason(), "Xử phạt vi phạm uy tín");
        Instant now = Instant.now();
        Penalty penalty = new Penalty(
                UUID.randomUUID(),
                request.userId(),
                normalizedReason,
                recordedDelta,
                request.refType(),
                request.refId(),
                now
        );

        Penalty saved;
        try {
            saved = penaltyRepository.saveAndFlush(penalty);
        } catch (DataIntegrityViolationException duplicate) {
            Penalty raced = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                    request.userId(), request.refType(), request.refId())
                    .orElseThrow(() -> duplicate);
            return replayResponse(raced, rep);
        }

        log.info("Đã trừ {} điểm uy tín cho user [{}] (refType={}, refId={}, {} -> {}, delta={})",
                points, request.userId(), request.refType(), request.refId(), previousScore, newScore, appliedDelta);

        publishAfterCommit(new ReputationPenaltyAppliedEvent(
                saved.getId(),
                request.userId(),
                saved.getRefType(),
                saved.getRefId(),
                saved.getScoreDelta(),
                previousScore,
                newScore,
                newLevel,
                saved.getReason(),
                now
        ));
        if (previousLevel != newLevel) {
            publishAfterCommit(new TrustLevelChangedEvent(request.userId(), previousLevel, newLevel, newScore, now));
        }

        return toPenaltyResponse(saved, previousScore, newScore, newLevel, false);
    }

    @Override
    @Transactional
    public RatingResponse createRating(UUID raterId, CreateRatingRequest request) {
        if (raterId == null || request == null || request.orderId() == null || request.rateeId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin đánh giá không hợp lệ");
        }
        if (request.score() < 1 || request.score() > 5) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Điểm đánh giá phải nằm trong khoảng 1 đến 5");
        }
        if (raterId.equals(request.rateeId())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Không thể tự đánh giá chính mình");
        }
        if (ratingRepository == null) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "Hệ thống đánh giá chưa được khởi tạo");
        }

        if (ratingRepository.existsByOrderIdAndRaterId(request.orderId(), raterId)) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS,
                    "Bạn đã đánh giá đối tác cho đơn hàng này");
        }

        if (properties.isOrderVerificationEnabled()) {
            verifyOrderEligibility(raterId, request.orderId(), request.rateeId());
        }

        Reputation rateeRep = lockOrCreateReputation(request.rateeId());

        if (ratingRepository.existsByOrderIdAndRaterId(request.orderId(), raterId)) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS,
                    "Bạn đã đánh giá đối tác cho đơn hàng này");
        }

        int ratingDelta = ratingScoreDelta(request.score());
        int previousScore = rateeRep.getScore();
        TrustLevel previousLevel = rateeRep.getTrustLevel();
        int newScore = Math.max(0, previousScore + ratingDelta);
        TrustLevel newLevel = resolveTrustLevel(newScore);

        Instant now = Instant.now();
        String normalizedComment = normalizeComment(request.comment());
        Rating rating = new Rating(
                UUID.randomUUID(),
                raterId,
                request.rateeId(),
                request.orderId(),
                request.score(),
                normalizedComment,
                true,
                false,
                now
        );

        Rating saved;
        try {
            saved = ratingRepository.saveAndFlush(rating);
        } catch (DataIntegrityViolationException duplicate) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS,
                    "Bạn đã đánh giá đối tác cho đơn hàng này");
        }

        rateeRep.setScore(newScore);
        rateeRep.setTrustLevel(newLevel);
        reputationRepository.saveAndFlush(rateeRep);

        log.info("Đã lưu đánh giá [{}] (score={}, delta={}) cho user [{}]: {} -> {} ({})",
                saved.getId(), saved.getScore(), ratingDelta, request.rateeId(), previousScore, newScore, newLevel);

        publishAfterCommit(new UserRatedEvent(
                saved.getId(),
                saved.getOrderId(),
                raterId,
                request.rateeId(),
                saved.getScore(),
                ratingDelta,
                previousScore,
                newScore,
                newLevel,
                now
        ));
        if (ratingDelta != 0) {
            publishAfterCommit(new ReputationScoreUpdatedEvent(
                    request.rateeId(), "RATING", ratingDelta, previousScore, newScore, newLevel, now));
        }
        if (previousLevel != newLevel) {
            publishAfterCommit(new TrustLevelChangedEvent(request.rateeId(), previousLevel, newLevel, newScore, now));
        }

        return toRatingResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RatingResponse> getRatingsForUser(UUID userId, Pageable pageable) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người dùng không được để trống");
        }
        return ratingRepository.findByRateeIdAndDisputedFalseOrderByCreatedAtDesc(userId, pageable)
                .map(this::toRatingResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RatingResponse> getRatingsForOrder(UUID orderId) {
        if (orderId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã đơn hàng không được để trống");
        }
        return ratingRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
                .stream()
                .map(this::toRatingResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReputationEligibilityResponse checkEligibility(UUID userId, EligibilityAction action) {
        if (userId == null || action == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE,
                    "Mã người dùng và loại hành động đấu giá không được để trống");
        }
        Reputation rep = reputationRepository.findById(userId)
                .orElseGet(() -> new Reputation(userId, properties.getInitialScore(),
                        resolveTrustLevel(properties.getInitialScore())));
        int minRequired = action == EligibilityAction.CREATE_AUCTION
                ? properties.getMinReputationToSell()
                : properties.getMinReputationToBid();

        if (!properties.isReputationEnforcementEnabled()) {
            return new ReputationEligibilityResponse(
                    userId,
                    action,
                    true,
                    rep.getScore(),
                    minRequired,
                    rep.getTrustLevel(),
                    "Kiểm tra điều kiện uy tín đang tắt theo cấu hình hệ thống"
            );
        }

        boolean eligible = rep.getScore() >= minRequired;
        String actionLabel = action == EligibilityAction.CREATE_AUCTION ? "tạo phiên đấu giá" : "đặt giá thầu";
        String reason = eligible
                ? "Đủ điều kiện điểm uy tín để " + actionLabel
                : String.format("Điểm uy tín hiện tại (%d) thấp hơn mức tối thiểu (%d) để %s",
                        rep.getScore(), minRequired, actionLabel);

        return new ReputationEligibilityResponse(
                userId,
                action,
                eligible,
                rep.getScore(),
                minRequired,
                rep.getTrustLevel(),
                reason
        );
    }

    @Override
    @Transactional
    public ReputationSummaryResponse recordTransactionOutcome(RecordTransactionRequest request) {
        if (request == null || request.userId() == null || request.outcome() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin thống kê giao dịch không hợp lệ");
        }
        Reputation rep = lockOrCreateReputation(request.userId());
        rep.setTotalTx(rep.getTotalTx() + 1);
        if (request.outcome() == TransactionOutcome.SUCCESS) {
            rep.setSuccessTx(rep.getSuccessTx() + 1);
            if (request.auction()) {
                rep.setAuctionWon(rep.getAuctionWon() + 1);
            }
        } else {
            rep.setFailedTx(rep.getFailedTx() + 1);
            if (request.auction()) {
                rep.setAuctionFailed(rep.getAuctionFailed() + 1);
            }
        }
        reputationRepository.saveAndFlush(rep);
        return toSummaryResponse(rep);
    }

    @Override
    @Transactional
    public RatingResponse flagRatingDispute(
            UUID ratingId,
            UUID actorId,
            boolean isPrivileged,
            DisputeRatingRequest request) {
        if (ratingId == null || (!isPrivileged && actorId == null)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thiếu mã đánh giá hoặc danh tính người yêu cầu");
        }
        Rating rating = ratingRepository.findById(ratingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND, "Không tìm thấy bản ghi đánh giá"));

        if (!isPrivileged && !rating.getRateeId().equals(actorId)) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_OWNER,
                    "Chỉ người nhận đánh giá hoặc Quản trị viên/Hỗ trợ mới được gắn cờ khiếu nại");
        }
        if (rating.isDisputed()) {
            return toRatingResponse(rating);
        }

        rating.setDisputed(true);
        Rating saved = ratingRepository.saveAndFlush(rating);
        String reasonNote = (request != null && request.reason() != null) ? request.reason().trim() : "";
        log.info("Đã gắn cờ khiếu nại đánh giá [{}] bởi actor [{}] (privileged={}, reason='{}')",
                ratingId, actorId, isPrivileged, reasonNote);
        return toRatingResponse(saved);
    }

    @Override
    @Transactional
    public RatingResponse resolveRatingDispute(UUID ratingId, UUID adminId, ResolveDisputeRequest request) {
        if (ratingId == null || adminId == null || request == null
                || request.resolution() == null || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin xử lý khiếu nại không hợp lệ");
        }

        Rating rating = ratingRepository.findById(ratingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REVIEW_NOT_FOUND, "Không tìm thấy bản ghi đánh giá"));

        if (request.resolution() == DisputeResolution.DISMISS) {
            rating.setDisputed(false);
            Rating saved = ratingRepository.saveAndFlush(rating);
            log.info("Admin [{}] bác bỏ khiếu nại cho đánh giá [{}]: {}", adminId, ratingId, request.reason().trim());
            return toRatingResponse(saved);
        }

        Reputation rep = lockOrCreateReputation(rating.getRateeId());
        rating.setDisputed(true);
        ratingRepository.saveAndFlush(rating);

        // Chống hoàn tác điểm nhiều lần nếu Admin gửi lặp yêu cầu INVALIDATE trên cùng một ratingId.
        Optional<Penalty> existingReversal = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                rating.getRateeId(), PenaltyRefType.RATING, rating.getId());
        if (existingReversal.isPresent()) {
            return toRatingResponse(rating);
        }

        int originalDelta = rating.isAggregated() ? ratingScoreDelta(rating.getScore()) : 0;
        int reversalDelta = -originalDelta;
        int previousScore = rep.getScore();
        TrustLevel previousLevel = rep.getTrustLevel();
        int newScore = Math.max(0, previousScore + reversalDelta);
        TrustLevel newLevel = resolveTrustLevel(newScore);

        if (reversalDelta != 0) {
            rep.setScore(newScore);
            rep.setTrustLevel(newLevel);
            reputationRepository.saveAndFlush(rep);
        }

        Instant now = Instant.now();
        String auditReason = normalizeReason(
                "[ADMIN " + adminId + "] Hủy hiệu lực đánh giá: " + request.reason().trim(),
                "Hủy hiệu lực đánh giá do khiếu nại hợp lệ");
        try {
            penaltyRepository.saveAndFlush(new Penalty(
                    UUID.randomUUID(),
                    rating.getRateeId(),
                    auditReason,
                    reversalDelta,
                    PenaltyRefType.RATING,
                    rating.getId(),
                    now
            ));
        } catch (DataIntegrityViolationException duplicate) {
            return toRatingResponse(rating);
        }

        log.info("Admin [{}] hủy hiệu lực đánh giá [{}] (reversalDelta={}, {} -> {})",
                adminId, ratingId, reversalDelta, previousScore, newScore);

        if (reversalDelta != 0) {
            publishAfterCommit(new ReputationScoreUpdatedEvent(
                    rating.getRateeId(), "DISPUTE_INVALIDATED", reversalDelta, previousScore, newScore, newLevel, now));
        }
        if (previousLevel != newLevel) {
            publishAfterCommit(new TrustLevelChangedEvent(rating.getRateeId(), previousLevel, newLevel, newScore, now));
        }

        return toRatingResponse(rating);
    }

    @Override
    @Transactional
    public PenaltyResponse adminAdjustReputation(UUID adminId, AdminAdjustReputationRequest request) {
        if (adminId == null || request == null || request.userId() == null
                || request.reason() == null || request.reason().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin điều chỉnh điểm uy tín không hợp lệ");
        }
        if (request.scoreDelta() == 0 || Math.abs(request.scoreDelta()) > 200) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE,
                    "Mức điều chỉnh điểm phải khác 0 và nằm trong [-200, +200]");
        }

        UUID refId = request.refId() != null ? request.refId() : UUID.randomUUID();
        if (request.refId() != null) {
            Optional<Penalty> existingFast = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                    request.userId(), PenaltyRefType.MANUAL, refId);
            if (existingFast.isPresent()) {
                Reputation current = reputationRepository.findById(request.userId())
                        .orElseGet(() -> new Reputation(request.userId(), properties.getInitialScore(), TrustLevel.STANDARD));
                return replayResponse(existingFast.get(), current);
            }
        }

        Reputation rep = lockOrCreateReputation(request.userId());
        if (request.refId() != null) {
            Optional<Penalty> existingLocked = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                    request.userId(), PenaltyRefType.MANUAL, refId);
            if (existingLocked.isPresent()) {
                return replayResponse(existingLocked.get(), rep);
            }
        }

        int previousScore = rep.getScore();
        TrustLevel previousLevel = rep.getTrustLevel();
        int newScore = Math.max(0, previousScore + request.scoreDelta());
        TrustLevel newLevel = resolveTrustLevel(newScore);

        rep.setScore(newScore);
        rep.setTrustLevel(newLevel);
        reputationRepository.saveAndFlush(rep);

        Instant now = Instant.now();
        String auditReason = normalizeReason(
                "[ADMIN " + adminId + "] " + request.reason().trim(),
                "Điều chỉnh điểm uy tín thủ công");
        Penalty saved;
        try {
            saved = penaltyRepository.saveAndFlush(new Penalty(
                    UUID.randomUUID(),
                    request.userId(),
                    auditReason,
                    request.scoreDelta(),
                    PenaltyRefType.MANUAL,
                    refId,
                    now
            ));
        } catch (DataIntegrityViolationException duplicate) {
            Penalty raced = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                    request.userId(), PenaltyRefType.MANUAL, refId)
                    .orElseThrow(() -> duplicate);
            return replayResponse(raced, rep);
        }

        log.info("Admin [{}] điều chỉnh điểm uy tín user [{}]: delta={} ({} -> {}, level={})",
                adminId, request.userId(), request.scoreDelta(), previousScore, newScore, newLevel);

        publishAfterCommit(new ReputationScoreUpdatedEvent(
                request.userId(), "ADMIN_MANUAL", request.scoreDelta(), previousScore, newScore, newLevel, now));
        if (previousLevel != newLevel) {
            publishAfterCommit(new TrustLevelChangedEvent(request.userId(), previousLevel, newLevel, newScore, now));
        }

        return toPenaltyResponse(saved, previousScore, newScore, newLevel, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReputationSummaryResponse> adminGetReputations(TrustLevel trustLevel, Pageable pageable) {
        Page<Reputation> page = trustLevel != null
                ? reputationRepository.findByTrustLevelOrderByScoreDesc(trustLevel, pageable)
                : reputationRepository.findAll(pageable);
        return page.map(this::toSummaryResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<RatingResponse> adminGetRatings(Boolean disputedOnly, UUID rateeId, Pageable pageable) {
        boolean onlyDisputed = Boolean.TRUE.equals(disputedOnly);
        Page<Rating> page;
        if (rateeId != null && onlyDisputed) {
            page = ratingRepository.findByRateeIdAndDisputedTrueOrderByCreatedAtDesc(rateeId, pageable);
        } else if (rateeId != null) {
            page = ratingRepository.findByRateeIdOrderByCreatedAtDesc(rateeId, pageable);
        } else if (onlyDisputed) {
            page = ratingRepository.findByDisputedTrueOrderByCreatedAtDesc(pageable);
        } else {
            page = ratingRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return page.map(this::toRatingResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PenaltyResponse> adminGetPenalties(UUID userId, PenaltyRefType refType, Pageable pageable) {
        Page<Penalty> page;
        if (userId != null && refType != null) {
            page = penaltyRepository.findByUserIdAndRefTypeOrderByCreatedAtDesc(userId, refType, pageable);
        } else if (userId != null) {
            page = penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        } else if (refType != null) {
            page = penaltyRepository.findByRefTypeOrderByCreatedAtDesc(refType, pageable);
        } else {
            page = penaltyRepository.findAllByOrderByCreatedAtDesc(pageable);
        }
        return page.map(p -> {
            Reputation rep = reputationRepository.findById(p.getUserId()).orElse(null);
            int currentScore = rep != null ? rep.getScore() : properties.getInitialScore();
            TrustLevel currentLevel = rep != null ? rep.getTrustLevel() : TrustLevel.STANDARD;
            return toPenaltyResponse(p, currentScore, currentScore, currentLevel, false);
        });
    }

    protected static int ratingScoreDelta(int starScore) {
        return switch (starScore) {
            case 5 -> 3;
            case 4 -> 1;
            case 3 -> 0;
            case 2 -> -3;
            case 1 -> -5;
            default -> throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE,
                    "Điểm đánh giá phải nằm trong khoảng 1 đến 5");
        };
    }

    private ReputationSummaryResponse toSummaryResponse(Reputation rep) {
        long totalRatings = 0L;
        double avgRating = 0.0d;
        if (ratingRepository != null) {
            totalRatings = ratingRepository.countByRateeIdAndDisputedFalse(rep.getUserId());
            if (totalRatings > 0) {
                Double rawAvg = ratingRepository.findAverageScoreByRateeId(rep.getUserId());
                avgRating = rawAvg != null ? Math.round(rawAvg * 100.0d) / 100.0d : 0.0d;
            }
        }
        return new ReputationSummaryResponse(
                rep.getUserId(),
                rep.getScore(),
                rep.getTrustLevel(),
                rep.getTotalTx(),
                rep.getSuccessTx(),
                rep.getFailedTx(),
                rep.getAuctionWon(),
                rep.getAuctionFailed(),
                avgRating,
                totalRatings
        );
    }

    private void verifyOrderEligibility(UUID raterId, UUID orderId, UUID rateeId) {
        if (orderVerificationClient == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "Chưa cấu hình kết nối xác thực đơn hàng với commerce-service");
        }
        VerifiedOrder order = orderVerificationClient.verifyOrder(orderId);
        if (order == null || order.status() == null) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "Thông tin đơn hàng trả về không đầy đủ");
        }
        String status = order.status().toUpperCase(Locale.ROOT);
        if (!RATABLE_ORDER_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.REVIEW_ORDER_NOT_DELIVERED,
                    "Chỉ có thể đánh giá sau khi đơn hàng ở trạng thái DELIVERED hoặc COMPLETED");
        }
        boolean isBuyer = raterId.equals(order.buyerId());
        boolean isSeller = raterId.equals(order.sellerId());
        if (!isBuyer && !isSeller) {
            throw new BusinessException(ErrorCode.REVIEW_NOT_OWNER,
                    "Bạn không phải người mua hoặc người bán của đơn hàng này");
        }
        UUID expectedRateeId = isBuyer ? order.sellerId() : order.buyerId();
        if (!rateeId.equals(expectedRateeId)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE,
                    "Người được đánh giá không khớp với đối tác giao dịch của đơn hàng");
        }
    }

    private String normalizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }

    private String normalizeReason(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private RatingResponse toRatingResponse(Rating rating) {
        return new RatingResponse(
                rating.getId(),
                rating.getOrderId(),
                rating.getRaterId(),
                rating.getRateeId(),
                rating.getScore(),
                rating.getComment(),
                rating.isAggregated(),
                rating.isDisputed(),
                rating.getCreatedAt()
        );
    }

    private PenaltyResponse toPenaltyResponse(
            Penalty penalty,
            int previousScore,
            int newScore,
            TrustLevel trustLevel,
            boolean idempotentReplay) {
        return new PenaltyResponse(
                penalty.getId(),
                penalty.getUserId(),
                penalty.getRefType(),
                penalty.getRefId(),
                penalty.getScoreDelta(),
                previousScore,
                newScore,
                trustLevel,
                penalty.getReason(),
                idempotentReplay,
                penalty.getCreatedAt()
        );
    }

    private Reputation lockOrCreateReputation(UUID userId) {
        return reputationRepository.findByUserIdForUpdate(userId).orElseGet(() -> {
            try {
                reputationRepository.saveAndFlush(new Reputation(userId, properties.getInitialScore(), TrustLevel.STANDARD));
            } catch (DataIntegrityViolationException ignored) {
                // Luồng khác vừa tạo cùng thời điểm; đọc khóa lại ở bước kế tiếp.
            }
            return reputationRepository.findByUserIdForUpdate(userId)
                    .orElseGet(() -> reputationRepository.findById(userId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR,
                                    "Không thể khởi tạo hồ sơ điểm uy tín")));
        });
    }

    private int resolvePenaltyPoints(ApplyPenaltyRequest request) {
        if (request.penaltyPoints() != null) {
            return request.penaltyPoints();
        }
        if (request.refType() == PenaltyRefType.AUCTION) {
            return properties.getPaymentTimeoutPenaltyScore();
        }
        throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Số điểm phạt không được để trống");
    }

    private TrustLevel resolveTrustLevel(int score) {
        if (score < properties.getLowThreshold()) {
            return TrustLevel.LOW;
        }
        if (score >= properties.getVipThreshold()) {
            return TrustLevel.VIP;
        }
        if (score >= properties.getHighThreshold()) {
            return TrustLevel.HIGH;
        }
        return TrustLevel.STANDARD;
    }

    private PenaltyResponse replayResponse(Penalty penalty, Reputation rep) {
        return toPenaltyResponse(penalty, rep.getScore(), rep.getScore(), rep.getTrustLevel(), true);
    }

    private void publishAfterCommit(Object event) {
        if (eventPublisher == null || event == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }
}
