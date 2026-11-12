package com.nexus.reputation.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.client.OrderVerificationClient;
import com.nexus.reputation.client.OrderVerificationClient.VerifiedOrder;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.dto.RecordTransactionRequest;
import com.nexus.reputation.dto.RecordTransactionRequest.TransactionOutcome;
import com.nexus.reputation.dto.ReputationEligibilityResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
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
 * Cài đặt nghiệp vụ điểm uy tín, đánh giá hai chiều, tính TrustLevel, điều kiện đấu giá và xử phạt (SRS §3.5.3).
 *
 * Quy tắc tính điểm đánh giá:
 * - 5 sao -> +3 điểm; 4 sao -> +1 điểm; 3 sao -> 0 điểm; 2 sao -> -3 điểm; 1 sao -> -5 điểm (sàn 0).
 * - Cập nhật đồng thời avgRating/totalRatings (loại trừ đánh giá bị khiếu nại) và TrustLevel.
 * - Phát sự kiện UserRatedEvent, ReputationScoreUpdatedEvent, TrustLevelChangedEvent sau commit DB.
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
                .map(p -> new PenaltyResponse(
                        p.getId(),
                        p.getUserId(),
                        p.getRefType(),
                        p.getRefId(),
                        p.getScoreDelta(),
                        currentScore,
                        currentScore,
                        currentLevel,
                        p.getReason(),
                        false,
                        p.getCreatedAt()));
    }

    @Override
    @Transactional
    public PenaltyResponse applyPenalty(ApplyPenaltyRequest request) {
        if (request == null || request.userId() == null || request.refType() == null || request.refId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Thông tin xử phạt không hợp lệ");
        }

        // Bước 1: Phản hồi idempotent nhanh nếu biên bản phạt cho cùng (user, refType, refId) đã tồn tại.
        Optional<Penalty> existingFast = penaltyRepository.findByUserIdAndRefTypeAndRefId(
                request.userId(), request.refType(), request.refId());
        if (existingFast.isPresent()) {
            Reputation current = reputationRepository.findById(request.userId())
                    .orElseGet(() -> new Reputation(request.userId(), properties.getInitialScore(), TrustLevel.STANDARD));
            return replayResponse(existingFast.get(), current);
        }

        // Bước 2: Khởi tạo (nếu mới) rồi khóa bi quan hồ sơ uy tín để tránh mất cập nhật khi trừ điểm đồng thời.
        Reputation rep = lockOrCreateReputation(request.userId());

        // Bước 3: Kiểm tra lại bên trong khóa dòng để chắc chắn không trừ điểm hai lần khi hai worker chạy song song.
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

        String normalizedReason = request.reason().trim();
        if (normalizedReason.length() > 500) {
            normalizedReason = normalizedReason.substring(0, 500);
        }
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

        return new PenaltyResponse(
                saved.getId(),
                saved.getUserId(),
                saved.getRefType(),
                saved.getRefId(),
                saved.getScoreDelta(),
                previousScore,
                newScore,
                newLevel,
                saved.getReason(),
                false,
                saved.getCreatedAt()
        );
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

        // Kiểm tra trùng trước khi gọi sang commerce-service để tiết kiệm round-trip không cần thiết.
        if (ratingRepository.existsByOrderIdAndRaterId(request.orderId(), raterId)) {
            throw new BusinessException(ErrorCode.REVIEW_ALREADY_EXISTS,
                    "Bạn đã đánh giá đối tác cho đơn hàng này");
        }

        if (properties.isOrderVerificationEnabled()) {
            verifyOrderEligibility(raterId, request.orderId(), request.rateeId());
        }

        // Khởi tạo và khóa bi quan hồ sơ rateeId để cập nhật điểm uy tín an toàn dưới đồng thời.
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
        return new PenaltyResponse(
                penalty.getId(),
                penalty.getUserId(),
                penalty.getRefType(),
                penalty.getRefId(),
                penalty.getScoreDelta(),
                rep.getScore(),
                rep.getScore(),
                rep.getTrustLevel(),
                penalty.getReason(),
                true,
                penalty.getCreatedAt()
        );
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
