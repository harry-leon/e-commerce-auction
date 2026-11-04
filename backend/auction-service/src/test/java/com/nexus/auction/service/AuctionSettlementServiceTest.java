package com.nexus.auction.service;

import com.nexus.auction.dto.mapper.AuctionSettlementMapper;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.entity.Bid;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.repository.BidRepository;
import com.nexus.auction.service.impl.AuctionSettlementServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test kiểm thử logic quyết toán phiên đấu giá và xác định người thắng (DoD HG-S2-1).
 */
@ExtendWith(MockitoExtension.class)
class AuctionSettlementServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionSettlementRepository auctionSettlementRepository;

    private Clock fixedClock;
    private Instant now;
    private AuctionSettlementService settlementService;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-11-03T10:00:00Z");
        fixedClock = Clock.fixed(now, ZoneOffset.UTC);
        settlementService = new AuctionSettlementServiceImpl(
                auctionRepository,
                bidRepository,
                auctionSettlementRepository,
                fixedClock);
    }

    private Auction createSampleAuction(UUID id, AuctionStatus status, Instant endTime) {
        Auction auction = new Auction();
        auction.setId(id);
        auction.setProductId(UUID.randomUUID());
        auction.setSellerId(UUID.randomUUID());
        auction.setStartPrice(new BigDecimal("100000.00"));
        auction.setBidIncrement(new BigDecimal("10000.00"));
        auction.setCurrentPrice(new BigDecimal("100000.00"));
        auction.setVisibility(AuctionVisibility.PUBLIC);
        auction.setStatus(status);
        auction.setStartTime(now.minus(2, ChronoUnit.HOURS));
        auction.setEndTime(endTime);
        auction.setExtensionCount(0);
        auction.setVersion(1);
        return auction;
    }

    @Test
    @DisplayName("Settle thành công: Có lượt bid hợp lệ -> Xác định Winner, giá chốt và kết quả SUCCESS")
    void testSettleAuction_WithValidBids_SetsWinnerAndSuccess() {
        UUID auctionId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        BigDecimal winningAmount = new BigDecimal("150000.00");

        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        Bid highestBid = new Bid();
        highestBid.setId(UUID.randomUUID());
        highestBid.setAuctionId(auctionId);
        highestBid.setBidderId(buyerId);
        highestBid.setAmount(winningAmount);
        highestBid.setPlacedAt(now.minus(10, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId))
                .thenReturn(Optional.of(highestBid));
        when(auctionSettlementRepository.save(any(AuctionSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getAuctionId()).isEqualTo(auctionId);
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);
        assertThat(response.getWinnerId()).isEqualTo(buyerId);
        assertThat(response.getFinalPrice()).isEqualTo(winningAmount);
        assertThat(response.getPaymentDeadline()).isEqualTo(now.plus(24, ChronoUnit.HOURS));

        // Kiểm tra auction được cập nhật sang SETTLED
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SETTLED);
        assertThat(auction.getHighestBidderId()).isEqualTo(buyerId);
        assertThat(auction.getCurrentPrice()).isEqualTo(winningAmount);

        verify(auctionRepository).save(auction);
        verify(auctionSettlementRepository).save(any(AuctionSettlement.class));
    }

    @Test
    @DisplayName("Settle thất bại: Không có lượt bid nào -> Kết quả FAILED, winner_id null, auction status ENDED")
    void testSettleAuction_WithoutBids_SetsFailed() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(1, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId))
                .thenReturn(Optional.empty());
        when(auctionSettlementRepository.save(any(AuctionSettlement.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isEqualTo(SettlementResult.FAILED);
        assertThat(response.getWinnerId()).isNull();
        assertThat(response.getFinalPrice()).isNull();
        assertThat(response.getPaymentDeadline()).isNull();

        // Kiểm tra auction được chuyển thành ENDED
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.ENDED);

        verify(auctionRepository).save(auction);
        verify(auctionSettlementRepository).save(any(AuctionSettlement.class));
    }

    @Test
    @DisplayName("Tính lũy kế (Idempotency): Phiên đã settle trước đó -> Trả về kết quả hiện tại, không tạo mới")
    void testSettleAuction_AlreadySettled_ReturnsExistingSettlement() {
        UUID auctionId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        AuctionSettlement existing = new AuctionSettlement();
        existing.setId(UUID.randomUUID());
        existing.setAuctionId(auctionId);
        existing.setWinnerId(winnerId);
        existing.setFinalPrice(new BigDecimal("200000.00"));
        existing.setResult(SettlementResult.SUCCESS);
        existing.setSettledAt(now.minus(1, ChronoUnit.HOURS));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(existing));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(existing.getId());
        assertThat(response.getWinnerId()).isEqualTo(winnerId);
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);

        // Đảm bảo không gọi lock DB hay lưu mới
        verify(auctionRepository, never()).findByIdForUpdate(any());
        verify(auctionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối settle khi chưa đến thời gian kết thúc (now < end_time)")
    void testSettleAuction_BeforeEndTime_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        // Phiên kết thúc sau 30 phút nữa
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(30, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));

        verify(auctionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối settle phiên ở trạng thái SCHEDULED, DRAFT hoặc CANCELLED")
    void testSettleAuction_InvalidStatus_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.SCHEDULED, now.minus(5, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    @Test
    @DisplayName("Kiểm tra không tìm thấy phiên đấu giá -> Ném ResourceNotFoundException")
    void testSettleAuction_NotFound_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Settle định kỳ (settleExpiredAuctions): Quét danh sách hết hạn và quyết toán đúng số lượng")
    void testSettleExpiredAuctions() {
        UUID auc1 = UUID.randomUUID();
        UUID auc2 = UUID.randomUUID();

        Auction a1 = createSampleAuction(auc1, AuctionStatus.ACTIVE, now.minus(10, ChronoUnit.MINUTES));
        Auction a2 = createSampleAuction(auc2, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        when(auctionRepository.findByStatusAndEndTimeLessThanEqual(AuctionStatus.ACTIVE, now))
                .thenReturn(List.of(a1, a2));
        when(auctionSettlementRepository.findByAuctionId(auc1)).thenReturn(Optional.empty());
        when(auctionSettlementRepository.findByAuctionId(auc2)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auc1)).thenReturn(Optional.of(a1));
        when(auctionRepository.findByIdForUpdate(auc2)).thenReturn(Optional.of(a2));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(any())).thenReturn(Optional.empty());
        when(auctionSettlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int settledCount = settlementService.settleExpiredAuctions();

        assertThat(settledCount).isEqualTo(2);
    }

    @Test
    @DisplayName("canAcceptBid: Trả về true khi phiên ACTIVE và thời điểm bid trước endTime")
    void testCanAcceptBid_WhenActiveAndBeforeEndTime_ReturnsTrue() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(1, ChronoUnit.HOURS));

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("canAcceptBid: Trả về false khi phiên đã bị khóa / chuyển sang ENDED hoặc SETTLED")
    void testCanAcceptBid_WhenLockedOrEnded_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ENDED, now.minus(5, ChronoUnit.MINUTES));

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("canAcceptBid: Trả về false khi phiên đã có bản ghi settlement")
    void testCanAcceptBid_WhenSettlementExists_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(10, ChronoUnit.MINUTES));

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(true);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra truyền mã phiên null vào settleAuction.
     * Kỳ vọng: Ném BusinessException với mã INVALID_PARAM_VALUE.
     */
    @Test
    @DisplayName("settleAuction: auctionId là null -> ném BusinessException INVALID_PARAM_VALUE")
    void testSettleAuction_NullAuctionId_ThrowsException() {
        assertThatThrownBy(() -> settlementService.settleAuction(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name()));
    }

    /**
     * Kiểm tra truyền mã phiên null vào getSettlementByAuctionId.
     * Kỳ vọng: Ném BusinessException với mã INVALID_PARAM_VALUE.
     */
    @Test
    @DisplayName("getSettlementByAuctionId: auctionId là null -> ném BusinessException INVALID_PARAM_VALUE")
    void testGetSettlementByAuctionId_NullAuctionId_ThrowsException() {
        assertThatThrownBy(() -> settlementService.getSettlementByAuctionId(null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name()));
    }

    /**
     * Kiểm tra truyền tham số null vào canAcceptBid.
     * Kỳ vọng: Trả về false an toàn mà không phát sinh NPE.
     */
    @Test
    @DisplayName("canAcceptBid: Tham số null -> Trả về false")
    void testCanAcceptBid_NullArguments_ReturnsFalse() {
        assertThat(settlementService.canAcceptBid(null, now)).isFalse();
        assertThat(settlementService.canAcceptBid(UUID.randomUUID(), null)).isFalse();
    }

    /**
     * Kiểm tra từ chối quyết toán khi phiên đã có trạng thái SETTLED.
     * Kỳ vọng: Ném BusinessException với mã AUCTION_NOT_ACTIVE.
     */
    @Test
    @DisplayName("settleAuction: Phiên có trạng thái SETTLED -> Ném BusinessException")
    void testSettleAuction_SettledStatus_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.SETTLED, now.minus(10, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    /**
     * Kiểm tra tính chịu lỗi của settleExpiredAuctions khi 1 phiên bị lỗi và 1 phiên thành công.
     * Kỳ vọng: Phiên lỗi không làm dừng tiến trình, phiên sau vẫn được xử lý và trả về số lượng thành công = 1.
     */
    @Test
    @DisplayName("settleExpiredAuctions: Một phiên lỗi không ảnh hưởng phiên còn lại")
    void testSettleExpiredAuctions_OneFailsOneSucceeds_HandlesGracefully() {
        UUID auc1 = UUID.randomUUID();
        UUID auc2 = UUID.randomUUID();

        Auction a1 = createSampleAuction(auc1, AuctionStatus.ACTIVE, now.minus(10, ChronoUnit.MINUTES));
        Auction a2 = createSampleAuction(auc2, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        when(auctionRepository.findByStatusAndEndTimeLessThanEqual(AuctionStatus.ACTIVE, now))
                .thenReturn(List.of(a1, a2));
        // auc1 gặp lỗi DB
        when(auctionSettlementRepository.findByAuctionId(auc1)).thenThrow(new RuntimeException("DB Connection timeout"));
        // auc2 thành công
        when(auctionSettlementRepository.findByAuctionId(auc2)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auc2)).thenReturn(Optional.of(a2));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auc2)).thenReturn(Optional.empty());
        when(auctionSettlementRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int settledCount = settlementService.settleExpiredAuctions();

        assertThat(settledCount).isEqualTo(1);
    }

    /**
     * Kiểm tra quyết toán phiên đã ở trạng thái ENDED nhưng có lượt bid hợp lệ.
     * Kỳ vọng: Chuyển sang SETTLED, xác định Winner và kết quả SUCCESS.
     */
    @Test
    @DisplayName("settleAuction: Phiên đã ở trạng thái ENDED có bid -> Quyết toán thành công SETTLED")
    void testSettleAuction_EndedStatusWithBids_SetsSettledAndSuccess() {
        UUID auctionId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        BigDecimal winningAmount = new BigDecimal("300000.00");

        Auction auction = createSampleAuction(auctionId, AuctionStatus.ENDED, now.minus(15, ChronoUnit.MINUTES));

        Bid highestBid = new Bid();
        highestBid.setId(UUID.randomUUID());
        highestBid.setAuctionId(auctionId);
        highestBid.setBidderId(buyerId);
        highestBid.setAmount(winningAmount);
        highestBid.setPlacedAt(now.minus(20, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId))
                .thenReturn(Optional.of(highestBid));
        when(auctionSettlementRepository.save(any(AuctionSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);
        assertThat(response.getWinnerId()).isEqualTo(buyerId);
        assertThat(auction.getStatus()).isEqualTo(AuctionStatus.SETTLED);
    }

    /**
     * Kiểm tra cơ chế Double-Checked Locking khi tiến trình khác chốt phiên ngay trước khi lấy lock.
     * Kỳ vọng: Trả về kết quả settlement vừa tạo bởi tiến trình song song mà không insert lại.
     */
    @Test
    @DisplayName("settleAuction: Phát hiện settlement sau khi lấy lock -> Trả về kết quả hiện tại")
    void testSettleAuction_ConcurrentSettlementDetectedAfterLock_ReturnsExistingSettlement() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        AuctionSettlement concurrentSettlement = new AuctionSettlement();
        concurrentSettlement.setId(UUID.randomUUID());
        concurrentSettlement.setAuctionId(auctionId);
        concurrentSettlement.setResult(SettlementResult.SUCCESS);
        concurrentSettlement.setFinalPrice(new BigDecimal("500000.00"));
        concurrentSettlement.setSettledAt(now);

        // Lần kiểm tra 1 (trước lock): chưa có; Lần kiểm tra 2 (sau lock): thread khác đã kịp insert
        when(auctionSettlementRepository.findByAuctionId(auctionId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentSettlement));
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(concurrentSettlement.getId());
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);

        verify(auctionSettlementRepository, never()).save(any());
    }

    /**
     * Kiểm tra bắt ngoại lệ xung đột Unique Constraint khi lưu settlement.
     * Kỳ vọng: Đọc lại bản ghi vừa được commit và trả về an toàn.
     */
    @Test
    @DisplayName("settleAuction: Bắt DataIntegrityViolationException và đọc lại bản ghi gốc thành công")
    void testSettleAuction_UniqueConstraintConflictHandledGracefully() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        AuctionSettlement existingSettlement = new AuctionSettlement();
        existingSettlement.setId(UUID.randomUUID());
        existingSettlement.setAuctionId(auctionId);
        existingSettlement.setResult(SettlementResult.SUCCESS);
        existingSettlement.setFinalPrice(new BigDecimal("100000.00"));

        when(auctionSettlementRepository.findByAuctionId(auctionId))
                .thenReturn(Optional.empty()) // Lần 1: Idempotency trước lock
                .thenReturn(Optional.empty()) // Lần 2: Sau lock
                .thenReturn(Optional.of(existingSettlement)); // Lần 3: Sau khi bắt catch exception

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId)).thenReturn(Optional.empty());
        when(auctionSettlementRepository.save(any(AuctionSettlement.class)))
                .thenThrow(new DataIntegrityViolationException("Duplicate key uq_settlement_auction"));

        AuctionSettlementResponse response = settlementService.settleAuction(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(existingSettlement.getId());
    }

    /**
     * Kiểm tra trường hợp DataIntegrityViolationException xảy ra nhưng không tìm thấy bản ghi gốc.
     * Kỳ vọng: Ném lại ngoại lệ DataIntegrityViolationException.
     */
    @Test
    @DisplayName("settleAuction: Ném lại DataIntegrityViolationException khi không tìm thấy bản ghi gốc")
    void testSettleAuction_UniqueConstraintConflictRethrownWhenSettlementNotFound() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId))
                .thenReturn(Optional.empty()); // Cả trước lock, sau lock và trong catch đều empty

        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId)).thenReturn(Optional.empty());
        when(auctionSettlementRepository.save(any(AuctionSettlement.class)))
                .thenThrow(new DataIntegrityViolationException("Database constraint violation"));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Kiểm tra tra cứu kết quả quyết toán thành công qua getSettlementByAuctionId.
     * Kỳ vọng: Trả về AuctionSettlementResponse khớp với entity.
     */
    @Test
    @DisplayName("getSettlementByAuctionId: Tra cứu thành công bản ghi settlement hiện hữu")
    void testGetSettlementByAuctionId_Success() {
        UUID auctionId = UUID.randomUUID();
        AuctionSettlement settlement = new AuctionSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setAuctionId(auctionId);
        settlement.setFinalPrice(new BigDecimal("250000.00"));
        settlement.setResult(SettlementResult.SUCCESS);
        settlement.setSettledAt(now);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));

        AuctionSettlementResponse response = settlementService.getSettlementByAuctionId(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getAuctionId()).isEqualTo(auctionId);
        assertThat(response.getFinalPrice()).isEqualTo(new BigDecimal("250000.00"));
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);
    }

    /**
     * Kiểm tra tra cứu kết quả quyết toán không tồn tại qua getSettlementByAuctionId.
     * Kỳ vọng: Ném ResourceNotFoundException.
     */
    @Test
    @DisplayName("getSettlementByAuctionId: Không tìm thấy kết quả -> Ném ResourceNotFoundException")
    void testGetSettlementByAuctionId_NotFound_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> settlementService.getSettlementByAuctionId(auctionId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Kiểm tra canAcceptBid khi phiên không tồn tại trong database.
     * Kỳ vọng: Trả về false.
     */
    @Test
    @DisplayName("canAcceptBid: Không tìm thấy phiên đấu giá -> Trả về false")
    void testCanAcceptBid_AuctionNotFound_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.empty());

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra canAcceptBid khi thời điểm bid trước thời gian mở phiên (bidTime < startTime).
     * Kỳ vọng: Trả về false.
     */
    @Test
    @DisplayName("canAcceptBid: Lượt bid gửi trước thời gian mở phiên -> Trả về false")
    void testCanAcceptBid_BidBeforeStartTime_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(1, ChronoUnit.HOURS));
        auction.setStartTime(now);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        // bidTime xảy ra trước startTime 1 giây
        boolean allowed = settlementService.canAcceptBid(auctionId, now.minus(1, ChronoUnit.SECONDS));

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra canAcceptBid khi thời điểm bid trùng với thời gian mở phiên (bidTime == startTime).
     * Kỳ vọng: Trả về true (biên hợp lệ).
     */
    @Test
    @DisplayName("canAcceptBid: Lượt bid gửi đúng thời điểm mở phiên -> Trả về true")
    void testCanAcceptBid_BidAtStartTime_ReturnsTrue() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(1, ChronoUnit.HOURS));
        auction.setStartTime(now);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isTrue();
    }

    /**
     * Kiểm tra chuyển đổi AuctionSettlementMapper khi thực thể null.
     * Kỳ vọng: Trả về null an toàn.
     */
    @Test
    @DisplayName("AuctionSettlementMapper: Thực thể null -> Trả về null")
    void testAuctionSettlementMapper_NullEntity_ReturnsNull() {
        assertThat(AuctionSettlementMapper.toResponse(null)).isNull();
    }

    /**
     * Kiểm tra từ chối quyết toán khi phiên ở trạng thái DRAFT.
     * Kỳ vọng: Ném BusinessException với mã AUCTION_NOT_ACTIVE.
     */
    @Test
    @DisplayName("settleAuction: Phiên ở trạng thái DRAFT -> Ném BusinessException")
    void testSettleAuction_DraftStatus_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.DRAFT, now.minus(5, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    /**
     * Kiểm tra từ chối quyết toán khi phiên ở trạng thái CANCELLED.
     * Kỳ vọng: Ném BusinessException với mã AUCTION_NOT_ACTIVE.
     */
    @Test
    @DisplayName("settleAuction: Phiên ở trạng thái CANCELLED -> Ném BusinessException")
    void testSettleAuction_CancelledStatus_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.CANCELLED, now.minus(5, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    /**
     * Kiểm tra từ chối quyết toán khi trạng thái phiên là null.
     * Kỳ vọng: Ném BusinessException với mã AUCTION_NOT_ACTIVE.
     */
    @Test
    @DisplayName("settleAuction: Trạng thái phiên là null -> Ném BusinessException")
    void testSettleAuction_NullStatus_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, null, now.minus(5, ChronoUnit.MINUTES));

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    /**
     * Kiểm tra từ chối quyết toán khi mốc thời gian endTime của phiên bị null.
     * Kỳ vọng: Ném BusinessException với mã AUCTION_NOT_ACTIVE.
     */
    @Test
    @DisplayName("settleAuction: Thời gian kết thúc endTime là null -> Ném BusinessException")
    void testSettleAuction_NullEndTime_ThrowsException() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, null);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
        when(auctionRepository.findByIdForUpdate(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> settlementService.settleAuction(auctionId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name()));
    }

    /**
     * Kiểm tra canAcceptBid khi thời điểm bid trùng đúng mốc endTime (bidTime == endTime).
     * Kỳ vọng: Trả về false vì phiên đóng tại mốc endTime.
     */
    @Test
    @DisplayName("canAcceptBid: Lượt bid gửi đúng thời điểm kết thúc endTime -> Trả về false")
    void testCanAcceptBid_BidAtEndTime_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Instant endTime = now.plus(1, ChronoUnit.HOURS);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, endTime);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, endTime);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra canAcceptBid khi thời điểm bid sau mốc endTime trong khi trạng thái vẫn còn ACTIVE.
     * Kỳ vọng: Trả về false.
     */
    @Test
    @DisplayName("canAcceptBid: Lượt bid gửi sau thời điểm kết thúc endTime -> Trả về false")
    void testCanAcceptBid_BidAfterEndTime_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Instant endTime = now.minus(1, ChronoUnit.MINUTES);
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, endTime);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra canAcceptBid khi phiên có startTime bị null.
     * Kỳ vọng: Trả về false an toàn mà không phát sinh NPE.
     */
    @Test
    @DisplayName("canAcceptBid: Phiên có startTime là null -> Trả về false")
    void testCanAcceptBid_NullStartTime_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.plus(1, ChronoUnit.HOURS));
        auction.setStartTime(null);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra canAcceptBid khi phiên có endTime bị null.
     * Kỳ vọng: Trả về false an toàn mà không phát sinh NPE.
     */
    @Test
    @DisplayName("canAcceptBid: Phiên có endTime là null -> Trả về false")
    void testCanAcceptBid_NullEndTime_ReturnsFalse() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, null);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        boolean allowed = settlementService.canAcceptBid(auctionId, now);

        assertThat(allowed).isFalse();
    }

    /**
     * Kiểm tra settleExpiredAuctions khi không có phiên nào hết hạn.
     * Kỳ vọng: Trả về 0 và không thực hiện quyết toán.
     */
    @Test
    @DisplayName("settleExpiredAuctions: Không có phiên hết hạn -> Trả về 0")
    void testSettleExpiredAuctions_ZeroExpired_ReturnsZero() {
        when(auctionRepository.findByStatusAndEndTimeLessThanEqual(AuctionStatus.ACTIVE, now))
                .thenReturn(List.of());

        int count = settlementService.settleExpiredAuctions();

        assertThat(count).isEqualTo(0);
    }

    /**
     * Kiểm tra settleExpiredAuctions khi self-proxy được tiêm vào service.
     * Kỳ vọng: Lệnh quyết toán được ủy quyền qua self-proxy để tạo transaction riêng biệt.
     */
    @Test
    @DisplayName("settleExpiredAuctions: Ủy quyền qua self-proxy khi có proxy được tiêm")
    void testSettleExpiredAuctions_WithSelfProxy_DelegatesThroughProxy() {
        UUID auctionId = UUID.randomUUID();
        Auction auction = createSampleAuction(auctionId, AuctionStatus.ACTIVE, now.minus(5, ChronoUnit.MINUTES));

        when(auctionRepository.findByStatusAndEndTimeLessThanEqual(AuctionStatus.ACTIVE, now))
                .thenReturn(List.of(auction));

        AuctionSettlementService mockProxy = org.mockito.Mockito.mock(AuctionSettlementService.class);
        when(mockProxy.settleAuction(auctionId)).thenReturn(new AuctionSettlementResponse());

        AuctionSettlementServiceImpl impl = (AuctionSettlementServiceImpl) settlementService;
        impl.setSelf(mockProxy);
        try {
            int count = settlementService.settleExpiredAuctions();
            assertThat(count).isEqualTo(1);
            verify(mockProxy).settleAuction(auctionId);
        } finally {
            impl.setSelf(null);
        }
    }
}
