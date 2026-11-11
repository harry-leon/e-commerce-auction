package com.nexus.reputation.service.impl;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.ApplyPenaltyRequest;
import com.nexus.reputation.dto.PenaltyResponse;
import com.nexus.reputation.dto.ReputationSummaryResponse;
import com.nexus.reputation.entity.Penalty;
import com.nexus.reputation.entity.Reputation;
import com.nexus.reputation.enums.PenaltyRefType;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.event.ReputationPenaltyAppliedEvent;
import com.nexus.reputation.repository.PenaltyRepository;
import com.nexus.reputation.repository.ReputationRepository;
import com.nexus.reputation.service.ReputationService;
import java.time.Instant;
import java.util.Optional;
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
 * Cài đặt nghiệp vụ điểm uy tín và xử phạt vi phạm quá hạn thanh toán đấu giá (SRS §3.5.3, KH-S2-3).
 *
 * Luồng xử lý applyPenalty:
 * 1. Kiểm tra bản ghi phạt trùng (userId, refType, refId) -> trả ngay bản ghi cũ nếu đã xử lý (Idempotent).
 * 2. Đảm bảo tồn tại dòng reputations của người dùng rồi khóa bi quan (PESSIMISTIC_WRITE).
 * 3. Kiểm tra lại dưới khóa phòng cạnh tranh đồng thời; nếu chưa có thì trừ điểm (không âm dưới 0),
 *    tăng failedTx/totalTx (và auctionFailed nếu refType == AUCTION), cập nhật TrustLevel.
 * 4. Lưu Penalty (scoreDelta = -points), phát sự kiện ReputationPenaltyAppliedEvent sau commit.
 */
@Service
public class ReputationServiceImpl implements ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationServiceImpl.class);

    private final ReputationRepository reputationRepository;
    private final PenaltyRepository penaltyRepository;
    private final ReputationProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public ReputationServiceImpl(
            ReputationRepository reputationRepository,
            PenaltyRepository penaltyRepository,
            ReputationProperties properties,
            @Autowired(required = false) ApplicationEventPublisher eventPublisher) {
        this.reputationRepository = reputationRepository;
        this.penaltyRepository = penaltyRepository;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public ReputationSummaryResponse getReputation(UUID userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã người dùng không được để trống");
        }
        return reputationRepository.findById(userId)
                .map(rep -> new ReputationSummaryResponse(
                        rep.getUserId(),
                        rep.getScore(),
                        rep.getTrustLevel(),
                        rep.getTotalTx(),
                        rep.getSuccessTx(),
                        rep.getFailedTx(),
                        rep.getAuctionWon(),
                        rep.getAuctionFailed()))
                .orElseGet(() -> new ReputationSummaryResponse(
                        userId,
                        properties.getInitialScore(),
                        TrustLevel.STANDARD,
                        0,
                        0,
                        0,
                        0,
                        0));
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
        int newScore = Math.max(0, previousScore - points);
        int appliedDelta = newScore - previousScore;
        int recordedDelta = -points;

        rep.setScore(newScore);
        rep.setTrustLevel(resolveTrustLevel(newScore));
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

        publishPenaltyApplied(new ReputationPenaltyAppliedEvent(
                saved.getId(),
                request.userId(),
                saved.getRefType(),
                saved.getRefId(),
                saved.getScoreDelta(),
                previousScore,
                newScore,
                rep.getTrustLevel(),
                saved.getReason(),
                now
        ));

        return new PenaltyResponse(
                saved.getId(),
                saved.getUserId(),
                saved.getRefType(),
                saved.getRefId(),
                saved.getScoreDelta(),
                previousScore,
                newScore,
                rep.getTrustLevel(),
                saved.getReason(),
                false,
                saved.getCreatedAt()
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

    private void publishPenaltyApplied(ReputationPenaltyAppliedEvent event) {
        if (eventPublisher == null) {
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
