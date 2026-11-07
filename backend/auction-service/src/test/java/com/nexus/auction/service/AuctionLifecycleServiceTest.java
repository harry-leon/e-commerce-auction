package com.nexus.auction.service;

import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.event.AuctionStartedEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.service.impl.AuctionLifecycleServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit Test kiểm thử toàn diện cho AuctionLifecycleServiceImpl (HG-S2-4).
 *
 * Kiểm tra các tiêu chuẩn nghiệm thu (DoD):
 * 1. Tự động mở phiên (Open Auction): chuyển SCHEDULED sang ACTIVE khi startTime <= now.
 * 2. Validate trạng thái (Status Validation): từ chối mở khi trạng thái không phải SCHEDULED.
 * 3. Chống thực thi trùng lặp (Prevent Duplicate Execution): bỏ qua an toàn nếu phiên đã mở hoặc đã kết thúc.
 * 4. Chống mở trước giờ (Timing Validation): bỏ qua nếu startTime > now.
 * 5. Bàn giao quyết toán (Settlement Handoff): gọi sang AuctionSettlementService khi đóng phiên.
 * 6. Xử lý lỗi độc lập và retry an toàn (Safe Retry): lỗi 1 phiên không làm hỏng cả lô.
 * 7. Phát Domain Event: AuctionStartedEvent được phát đi với đầy đủ thông tin.
 */
@ExtendWith(MockitoExtension.class)
class AuctionLifecycleServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionSettlementService auctionSettlementService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Clock fixedClock;
    private Instant now;
    private AuctionLifecycleServiceImpl lifecycleService;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-07T10:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        lifecycleService = new AuctionLifecycleServiceImpl(
                auctionRepository,
                auctionSettlementService,
                eventPublisher,
                fixedClock
        );
        lifecycleService.setSelf(lifecycleService);
    }

    private Auction createSampleAuction(UUID id, AuctionStatus status, Instant startTime, Instant endTime) {
        Auction auction = new Auction();
        auction.setId(id);
        auction.setProductId(UUID.randomUUID());
        auction.setSellerId(UUID.randomUUID());
        auction.setStartPrice(new BigDecimal("5000000.00"));
        auction.setBidIncrement(new BigDecimal("100000.00"));
        auction.setCurrentPrice(new BigDecimal("5000000.00"));
        auction.setVisibility(AuctionVisibility.PUBLIC);
        auction.setStatus(status);
        auction.setStartTime(startTime);
        auction.setEndTime(endTime);
        auction.setExtensionCount(0);
        auction.setVersion(1);
        return auction;
    }

    @Test
    @DisplayName("openAuction: Phiên SCHEDULED đến giờ mở -> Chuyển sang ACTIVE và phát AuctionStartedEvent")
    void openAuction_ValidScheduledAuction_StatusTransitionsToActive_AndPublishesEvent() {
        UUID auctionId = UUID.randomUUID();
        Instant startTime = now.minusSeconds(60); // Đã qua thời điểm mở 1 phút
        Instant endTime = now.plusSeconds(3600);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.SCHEDULED, startTime, endTime);

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = lifecycleService.openAuction(auctionId);

        assertThat(result).isTrue();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        verify(auctionRepository).save(auction);

        ArgumentCaptor<AuctionStartedEvent> eventCaptor = ArgumentCaptor.forClass(AuctionStartedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AuctionStartedEvent publishedEvent = eventCaptor.getValue();
        assertThat(publishedEvent.auctionId()).isEqualTo(auctionId);
        assertThat(publishedEvent.startTime()).isEqualTo(startTime);
        assertThat(publishedEvent.endTime()).isEqualTo(endTime);
        assertThat(publishedEvent.startPrice()).isEqualByComparingTo("5000000.00");
    }

    @Test
    @DisplayName("openAuction: Phiên không ở trạng thái SCHEDULED (đã ACTIVE) -> Bỏ qua, chống thực thi trùng lặp")
    void openAuction_AlreadyActive_SkipsGracefully_PreventsDuplicateExecution() {
        UUID auctionId = UUID.randomUUID();
        Instant startTime = now.minusSeconds(60);
        Instant endTime = now.plusSeconds(3600);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, startTime, endTime);

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        boolean result = lifecycleService.openAuction(auctionId);

        assertThat(result).isFalse();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        verify(auctionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("openAuction: Phiên đã bị HỦY (CANCELLED) -> Bỏ qua, không chuyển sang ACTIVE")
    void openAuction_CancelledStatus_SkipsGracefully() {
        UUID auctionId = UUID.randomUUID();
        Instant startTime = now.minusSeconds(60);
        Instant endTime = now.plusSeconds(3600);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.CANCELLED, startTime, endTime);

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        boolean result = lifecycleService.openAuction(auctionId);

        assertThat(result).isFalse();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        verify(auctionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("openAuction: Chưa đến thời điểm startTime -> Bỏ qua, không mở trước giờ")
    void openAuction_NotYetStartTime_SkipsGracefully() {
        UUID auctionId = UUID.randomUUID();
        Instant startTime = now.plusSeconds(300); // 5 phút nữa mới mở
        Instant endTime = now.plusSeconds(7200);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.SCHEDULED, startTime, endTime);

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        boolean result = lifecycleService.openAuction(auctionId);

        assertThat(result).isFalse();
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        verify(auctionRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("openAuction: Mã phiên null -> Ném BusinessException INVALID_PARAM_VALUE")
    void openAuction_NullId_ThrowsInvalidParamValue() {
        assertThatThrownBy(() -> lifecycleService.openAuction(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_PARAM_VALUE.name());
    }

    @Test
    @DisplayName("openAuction: Không tìm thấy phiên -> Ném ResourceNotFoundException")
    void openAuction_NotFound_ThrowsResourceNotFoundException() {
        UUID auctionId = UUID.randomUUID();
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> lifecycleService.openAuction(auctionId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("openScheduledAuctions: Quét danh sách phiên SCHEDULED đến giờ và mở thành công")
    void openScheduledAuctions_OpensEligibleAuctions() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Auction auction1 = createSampleAuction(id1, AuctionStatus.SCHEDULED, now.minusSeconds(30), now.plusSeconds(3600));
        Auction auction2 = createSampleAuction(id2, AuctionStatus.SCHEDULED, now.minusSeconds(10), now.plusSeconds(3600));

        when(auctionRepository.findByStatusAndStartTimeLessThanEqual(AuctionStatus.SCHEDULED, now))
                .thenReturn(Arrays.asList(auction1, auction2));
        when(auctionRepository.findByIdForUpdate(id1)).thenReturn(Optional.of(auction1));
        when(auctionRepository.findByIdForUpdate(id2)).thenReturn(Optional.of(auction2));
        when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int count = lifecycleService.openScheduledAuctions();

        assertThat(count).isEqualTo(2);
        assertThat(auction1.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        assertThat(auction2.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("openScheduledAuctions: Một phiên gặp lỗi DB -> Bắt lỗi độc lập, phiên khác vẫn mở thành công (Safe Retry)")
    void openScheduledAuctions_OneFails_OtherSucceeds_SafeRetry() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Auction auction1 = createSampleAuction(id1, AuctionStatus.SCHEDULED, now.minusSeconds(30), now.plusSeconds(3600));
        Auction auction2 = createSampleAuction(id2, AuctionStatus.SCHEDULED, now.minusSeconds(10), now.plusSeconds(3600));

        when(auctionRepository.findByStatusAndStartTimeLessThanEqual(AuctionStatus.SCHEDULED, now))
                .thenReturn(Arrays.asList(auction1, auction2));

        // Phiên 1 bị lỗi DB Lock
        when(auctionRepository.findByIdForUpdate(id1)).thenThrow(new RuntimeException("Lock acquisition failure"));
        // Phiên 2 mở bình thường
        when(auctionRepository.findByIdForUpdate(id2)).thenReturn(Optional.of(auction2));
        when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int count = lifecycleService.openScheduledAuctions();

        assertThat(count).isEqualTo(1);
        assertThat(auction2.getStatus()).isEqualTo(AuctionStatus.ACTIVE);
    }

    @Test
    @DisplayName("openScheduledAuctions: Danh sách rỗng -> Trả về 0 không lỗi")
    void openScheduledAuctions_EmptyList_ReturnsZero() {
        when(auctionRepository.findByStatusAndStartTimeLessThanEqual(AuctionStatus.SCHEDULED, now))
                .thenReturn(Collections.emptyList());

        int count = lifecycleService.openScheduledAuctions();

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("closeAuction: Bàn giao quyết toán thành công qua AuctionSettlementService")
    void closeAuction_TriggersSettlementHandoff() {
        UUID auctionId = UUID.randomUUID();

        lifecycleService.closeAuction(auctionId);

        verify(auctionSettlementService).settleAuction(auctionId);
    }

    @Test
    @DisplayName("closeAuction: ID null -> Ném BusinessException INVALID_PARAM_VALUE")
    void closeAuction_NullId_ThrowsException() {
        assertThatThrownBy(() -> lifecycleService.closeAuction(null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.INVALID_PARAM_VALUE.name());
    }

    @Test
    @DisplayName("closeExpiredAuctions: Quét và đóng các phiên hết hạn qua AuctionSettlementService")
    void closeExpiredAuctions_DelegatesToSettlementService() {
        when(auctionSettlementService.settleExpiredAuctions()).thenReturn(5);

        int count = lifecycleService.closeExpiredAuctions();

        assertThat(count).isEqualTo(5);
        verify(auctionSettlementService).settleExpiredAuctions();
    }
}

