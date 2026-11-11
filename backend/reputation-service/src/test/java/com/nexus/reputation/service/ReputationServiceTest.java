package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
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
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Kiểm thử đơn vị cho ReputationServiceImpl: trừ 10 điểm khi quá hạn thanh toán 24h,
 * idempotent theo (userId, refType, refId), chặn điểm âm và phát sự kiện ReputationPenaltyAppliedEvent.
 */
@ExtendWith(MockitoExtension.class)
class ReputationServiceTest {

    @Mock
    private ReputationRepository reputationRepository;

    @Mock
    private PenaltyRepository penaltyRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReputationProperties properties;
    private ReputationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ReputationProperties();
        service = new ReputationServiceImpl(reputationRepository, penaltyRepository, properties, eventPublisher);
    }

    /** Trả hồ sơ mặc định 100 điểm STANDARD khi người dùng chưa từng phát sinh giao dịch uy tín. */
    @Test
    void getReputation_returnsDefaultWhenMissing() {
        UUID userId = UUID.randomUUID();
        when(reputationRepository.findById(userId)).thenReturn(Optional.empty());

        ReputationSummaryResponse summary = service.getReputation(userId);

        assertThat(summary.userId()).isEqualTo(userId);
        assertThat(summary.score()).isEqualTo(100);
        assertThat(summary.trustLevel()).isEqualTo(TrustLevel.STANDARD);
        assertThat(summary.auctionFailed()).isZero();
    }

    /** Áp dụng mặc định PAYMENT_TIMEOUT_PENALTY_SCORE = 10 khi refType = AUCTION và phát event sau khi lưu. */
    @Test
    void applyPenalty_deductsDefault10PointsForAuctionTimeout() {
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        Reputation rep = new Reputation(userId, 100, TrustLevel.STANDARD);
        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(userId, PenaltyRefType.AUCTION, auctionId))
                .thenReturn(Optional.empty(), Optional.empty());
        when(reputationRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rep));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.saveAndFlush(any(Penalty.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyResponse response = service.applyPenalty(new ApplyPenaltyRequest(
                userId, PenaltyRefType.AUCTION, auctionId, null, "Quá hạn thanh toán 24h"));

        assertThat(response.idempotentReplay()).isFalse();
        assertThat(response.scoreDelta()).isEqualTo(-10);
        assertThat(response.previousScore()).isEqualTo(100);
        assertThat(response.newScore()).isEqualTo(90);
        assertThat(response.trustLevel()).isEqualTo(TrustLevel.STANDARD);
        assertThat(rep.getTotalTx()).isEqualTo(1);
        assertThat(rep.getFailedTx()).isEqualTo(1);
        assertThat(rep.getAuctionFailed()).isEqualTo(1);

        ArgumentCaptor<ReputationPenaltyAppliedEvent> eventCaptor =
                ArgumentCaptor.forClass(ReputationPenaltyAppliedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().scoreDelta()).isEqualTo(-10);
        assertThat(eventCaptor.getValue().newScore()).isEqualTo(90);
    }

    /** Chặn replay cùng (userId, refType, refId) để không trừ điểm hai lần và không phát sự kiện lặp. */
    @Test
    void applyPenalty_replayReturnsExistingWithoutDoubleDeduction() {
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        Penalty existing = new Penalty(
                UUID.randomUUID(), userId, "Quá hạn 24h", -10, PenaltyRefType.AUCTION, auctionId, Instant.now());
        Reputation rep = new Reputation(userId, 90, TrustLevel.STANDARD);

        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(userId, PenaltyRefType.AUCTION, auctionId))
                .thenReturn(Optional.of(existing));
        when(reputationRepository.findById(userId)).thenReturn(Optional.of(rep));

        PenaltyResponse response = service.applyPenalty(new ApplyPenaltyRequest(
                userId, PenaltyRefType.AUCTION, auctionId, 10, "Quá hạn 24h"));

        assertThat(response.idempotentReplay()).isTrue();
        assertThat(response.newScore()).isEqualTo(90);
        verify(reputationRepository, never()).saveAndFlush(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /** Điểm uy tín không bao giờ âm (sàn 0) và hạ hạng xuống LOW khi tụt dưới ngưỡng 60. */
    @Test
    void applyPenalty_clampsAtZeroAndDowngradesToLow() {
        UUID userId = UUID.randomUUID();
        UUID auctionId = UUID.randomUUID();
        Reputation rep = new Reputation(userId, 5, TrustLevel.LOW);

        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(userId, PenaltyRefType.AUCTION, auctionId))
                .thenReturn(Optional.empty(), Optional.empty());
        when(reputationRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rep));
        when(reputationRepository.saveAndFlush(any(Reputation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(penaltyRepository.saveAndFlush(any(Penalty.class))).thenAnswer(inv -> inv.getArgument(0));

        PenaltyResponse response = service.applyPenalty(new ApplyPenaltyRequest(
                userId, PenaltyRefType.AUCTION, auctionId, 10, "Quá hạn thanh toán"));

        assertThat(response.newScore()).isZero();
        assertThat(response.trustLevel()).isEqualTo(TrustLevel.LOW);
    }

    /** Yêu cầu không có penaltyPoints cho loại non-auction phải bị từ chối thay vì gán ngầm. */
    @Test
    void applyPenalty_nonAuctionRequiresExplicitPoints() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Reputation rep = new Reputation(userId, 100, TrustLevel.STANDARD);
        when(penaltyRepository.findByUserIdAndRefTypeAndRefId(userId, PenaltyRefType.ORDER, orderId))
                .thenReturn(Optional.empty(), Optional.empty());
        when(reputationRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rep));

        assertThatThrownBy(() -> service.applyPenalty(new ApplyPenaltyRequest(
                userId, PenaltyRefType.ORDER, orderId, null, "Hủy đơn cố ý")))
                .isInstanceOf(BusinessException.class);
    }

    /** Tra cứu lịch sử phạt theo trang và kiểm tra validate tham số đầu vào null. */
    @Test
    @DisplayName("Lấy lịch sử phạt uy tín và từ chối userId rỗng")
    void getPenalties_returnsPageAndValidatesNullUserId() {
        UUID userId = UUID.randomUUID();
        Penalty penalty = new Penalty(
                UUID.randomUUID(), userId, "Quá hạn 24h", -10, PenaltyRefType.AUCTION, UUID.randomUUID(), Instant.now());
        when(reputationRepository.findById(userId))
                .thenReturn(Optional.of(new Reputation(userId, 90, TrustLevel.STANDARD)));
        when(penaltyRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(penalty)));

        Page<PenaltyResponse> page = service.getPenalties(userId, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().scoreDelta()).isEqualTo(-10);
        assertThatThrownBy(() -> service.getReputation(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.getPenalties(null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class);
    }
}
