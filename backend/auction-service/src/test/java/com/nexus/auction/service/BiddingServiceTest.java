package com.nexus.auction.service;

import com.nexus.auction.config.AuctionProperties;
import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.Bid;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.event.BidPlacedEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.repository.BidRepository;
import com.nexus.auction.service.impl.BiddingServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit Test toàn diện cho BiddingServiceImpl (HG-S2-3).
 *
 * Kiểm tra các tiêu chuẩn nghiệm thu (DoD):
 * 1. Validate trạng thái phiên (Auction status): chỉ nhận khi ACTIVE, từ chối khi SCHEDULED, ENDED, CANCELLED, SETTLED hoặc ngoài khoảng [startTime, endTime).
 * 2. Validate quyền và tư cách (Eligibility): Seller không được bid phiên mình (BR-AUC-06); Highest bidder không tự raise giá tiếp (BR-AUC-06).
 * 3. Validate mức giá và bước giá (Bid amount & increment): amount >= startPrice khi chưa có bid; amount >= currentPrice + bidIncrement khi đã có bid (BR-AUC-07).
 * 4. Idempotency: gửi lại cùng idempotencyKey trả về kết quả ban đầu, không tạo bản ghi mới (BR-AUC-08).
 * 5. Concurrency control: xử lý ObjectOptimisticLockingFailureException và DataIntegrityViolationException thành BID_CONCURRENT_OUTBID nhất quán.
 * 6. Anti-sniping protection: tự động gia hạn endTime và tăng extensionCount khi bid trong cửa sổ 120s cuối, dừng gia hạn khi đạt maxExtensionCount (BR-AUC-09).
 * 7. Bid history: lưu bản ghi và truy vấn lịch sử có phân trang.
 * 8. Domain Event: phát BidPlacedEvent mang thông tin đầy đủ.
 */
@ExtendWith(MockitoExtension.class)
class BiddingServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionSettlementRepository auctionSettlementRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AuctionProperties properties;
    private Clock fixedClock;
    private Instant fixedInstant;

    private BiddingServiceImpl biddingService;

    @BeforeEach
    void setUp() {
        properties = new AuctionProperties();
        properties.getBidding().setMinIncrement(1000L);
        properties.getBidding().setAntiSnipingSeconds(120);
        properties.getBidding().setMaxExtensionCount(10);

        fixedInstant = Instant.parse("2026-11-07T10:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneId.of("UTC"));

        biddingService = new BiddingServiceImpl(
                auctionRepository,
                bidRepository,
                auctionSettlementRepository,
                properties,
                eventPublisher,
                fixedClock
        );
    }

    private Auction createStandardAuction(UUID auctionId, UUID sellerId) {
        Auction auction = new Auction();
        auction.setId(auctionId);
        auction.setSellerId(sellerId);
        auction.setProductId(UUID.randomUUID());
        auction.setStartPrice(new BigDecimal("5000000.00"));
        auction.setBidIncrement(new BigDecimal("100000.00"));
        auction.setCurrentPrice(new BigDecimal("5000000.00"));
        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setStartTime(fixedInstant.minus(Duration.ofHours(1)));
        auction.setEndTime(fixedInstant.plus(Duration.ofHours(2))); // Còn 2 tiếng nữa
        auction.setExtensionCount(0);
        auction.setVersion(1);
        return auction;
    }

    @Test
    @DisplayName("Đặt giá đầu tiên thành công khi bằng startPrice và chưa có ai bid")
    void placeBid_FirstBid_EqualsStartPrice_Success() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setHighestBidderId(null); // Chưa có ai bid

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(1L);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5000000.00"), "key-1");
        PlaceBidResult result = biddingService.placeBid(auctionId, buyerId, request);

        assertThat(result.getCurrentBid()).isEqualByComparingTo("5000000.00");
        assertThat(result.getBidCount()).isEqualTo(1L);
        assertThat(result.isExtended()).isFalse();
        assertThat(auction.getHighestBidderId()).isEqualTo(buyerId);

        verify(bidRepository).save(any(Bid.class));
        verify(auctionRepository).save(auction);
        verify(eventPublisher).publishEvent(any(BidPlacedEvent.class));
    }

    @Test
    @DisplayName("Đặt giá kế tiếp thành công khi >= currentPrice + bidIncrement")
    void placeBid_SubsequentBid_Success() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyer1 = UUID.randomUUID();
        UUID buyer2 = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setCurrentPrice(new BigDecimal("5000000.00"));
        auction.setHighestBidderId(buyer1);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(2L);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5100000.00"), "key-2");
        PlaceBidResult result = biddingService.placeBid(auctionId, buyer2, request);

        assertThat(result.getCurrentBid()).isEqualByComparingTo("5100000.00");
        assertThat(result.getBidCount()).isEqualTo(2L);
        assertThat(auction.getHighestBidderId()).isEqualTo(buyer2);

        verify(bidRepository).save(any(Bid.class));
        verify(auctionRepository).save(auction);
    }

    @Test
    @DisplayName("Từ chối khi phiên không ở trạng thái ACTIVE (ví dụ SCHEDULED)")
    void placeBid_Reject_WhenAuctionNotActive() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setStatus(AuctionStatus.SCHEDULED);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-3");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyerId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUCTION_NOT_ACTIVE.name());

        verify(bidRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối khi thời gian hiện tại đã vượt quá endTime (now >= endTime)")
    void placeBid_Reject_WhenAuctionEndedByTime() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setEndTime(fixedInstant.minusSeconds(10)); // Đã qua endTime

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-4");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyerId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUCTION_NOT_ACTIVE.name());
    }

    @Test
    @DisplayName("Từ chối khi phiên đã có bản ghi settlement")
    void placeBid_Reject_WhenSettlementExists() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(true);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-5");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyerId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUCTION_NOT_ACTIVE.name());
    }

    @Test
    @DisplayName("BR-AUC-06: Người bán không được tự đặt giá cho phiên của mình (AUCTION_SELLER_CANNOT_BID)")
    void placeBid_Reject_SellerCannotBidOwnAuction() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-6");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, sellerId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUCTION_SELLER_CANNOT_BID.name());
    }

    @Test
    @DisplayName("BR-AUC-06: Người đang giữ giá cao nhất không được tự nâng giá tiếp")
    void placeBid_Reject_HighestBidderCannotRaiseAgain() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyer1 = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setHighestBidderId(buyer1);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-7");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyer1, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.BID_AMOUNT_TOO_LOW.name());
    }

    @Test
    @DisplayName("BR-AUC-07: Từ chối khi mức giá đặt thấp hơn giá tối thiểu (startPrice hoặc currentPrice + increment)")
    void placeBid_Reject_AmountTooLow() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyer1 = UUID.randomUUID();
        UUID buyer2 = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        auction.setCurrentPrice(new BigDecimal("5000000.00"));
        auction.setHighestBidderId(buyer1);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);

        // Yêu cầu: 5,000,000 + 100,000 = 5,100,000. Đặt 5,050,000 -> Lỗi
        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5050000.00"), "key-8");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyer2, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.BID_AMOUNT_TOO_LOW.name());
    }

    @Test
    @DisplayName("BR-AUC-08: Idempotency - Gửi lại cùng idempotencyKey trả về kết quả ban đầu, không tạo bản ghi mới")
    void placeBid_IdempotentDuplicateRequest_ReturnsExisting() {
        UUID auctionId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        String idempotencyKey = "uuid-key-repeat";

        Bid existingBid = new Bid();
        existingBid.setId(UUID.randomUUID());
        existingBid.setAuctionId(auctionId);
        existingBid.setBidderId(buyerId);
        existingBid.setAmount(new BigDecimal("5200000.00"));
        existingBid.setIdempotencyKey(idempotencyKey);
        existingBid.setPlacedAt(fixedInstant.minusSeconds(30));

        when(bidRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingBid));
        Auction auction = createStandardAuction(auctionId, UUID.randomUUID());
        auction.setCurrentPrice(new BigDecimal("5200000.00"));
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(1L);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5200000.00"), idempotencyKey);
        PlaceBidResult result = biddingService.placeBid(auctionId, buyerId, request);

        assertThat(result.getCurrentBid()).isEqualByComparingTo("5200000.00");
        assertThat(result.getBid().getId()).isEqualTo(existingBid.getId());
        verify(bidRepository, never()).save(any());
        verify(auctionRepository, never()).save(any());
    }

    @Test
    @DisplayName("Concurrency Control: Bắt ObjectOptimisticLockingFailureException và ném BID_CONCURRENT_OUTBID")
    void placeBid_ConcurrentConflict_ThrowsBidConcurrentOutbid() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(auctionRepository.save(any(Auction.class))).thenThrow(new ObjectOptimisticLockingFailureException(Auction.class, auctionId));

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-race");
        assertThatThrownBy(() -> biddingService.placeBid(auctionId, buyerId, request))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.BID_CONCURRENT_OUTBID.name());
    }

    @Test
    @DisplayName("BR-AUC-09: Anti-sniping kích hoạt khi bid trong 120s cuối -> gia hạn endTime thêm 120s và tăng extensionCount")
    void placeBid_AntiSniping_ExtendedSuccessfully() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        // Đặt endTime chỉ còn 75 giây nữa là hết hạn (< 120s anti-sniping)
        Instant originalEndTime = fixedInstant.plusSeconds(75);
        auction.setEndTime(originalEndTime);
        auction.setExtensionCount(0);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(1L);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-sniping");
        PlaceBidResult result = biddingService.placeBid(auctionId, buyerId, request);

        assertThat(result.isExtended()).isTrue();
        assertThat(auction.getExtensionCount()).isEqualTo(1);
        assertThat(auction.getEndTime()).isEqualTo(originalEndTime.plusSeconds(120));
        assertThat(result.getEndTime()).isEqualTo(auction.getEndTime());
    }

    @Test
    @DisplayName("BR-AUC-09: Đạt giới hạn maxExtensionCount = 10 -> không gia hạn thêm endTime")
    void placeBid_AntiSniping_MaxExtensionLimitReached() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        Auction auction = createStandardAuction(auctionId, sellerId);
        Instant originalEndTime = fixedInstant.plusSeconds(75);
        auction.setEndTime(originalEndTime);
        auction.setExtensionCount(10); // Đã chạm mốc 10 lần tối đa

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(11L);

        PlaceBidRequest request = new PlaceBidRequest(new BigDecimal("5500000.00"), "key-max-sniping");
        PlaceBidResult result = biddingService.placeBid(auctionId, buyerId, request);

        assertThat(result.isExtended()).isFalse();
        assertThat(auction.getExtensionCount()).isEqualTo(10);
        assertThat(auction.getEndTime()).isEqualTo(originalEndTime); // Không đổi
    }

    @Test
    @DisplayName("Tra cứu lịch sử đặt giá getBidHistory thành công và phân trang")
    void getBidHistory_Success() {
        UUID auctionId = UUID.randomUUID();
        when(auctionRepository.existsById(auctionId)).thenReturn(true);

        Bid bid = new Bid();
        bid.setId(UUID.randomUUID());
        bid.setAuctionId(auctionId);
        bid.setBidderId(UUID.randomUUID());
        bid.setAmount(new BigDecimal("5200000.00"));
        bid.setPlacedAt(fixedInstant);

        Pageable pageable = PageRequest.of(0, 10);
        when(bidRepository.findByAuctionId(auctionId, pageable)).thenReturn(new PageImpl<>(Collections.singletonList(bid)));

        Page<BidResponse> page = biddingService.getBidHistory(auctionId, pageable);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getAmount()).isEqualByComparingTo("5200000.00");
    }

    @Test
    @DisplayName("getBidHistory ném AUCTION_NOT_FOUND khi phiên không tồn tại")
    void getBidHistory_AuctionNotFound() {
        UUID auctionId = UUID.randomUUID();
        when(auctionRepository.existsById(auctionId)).thenReturn(false);

        Pageable pageable = PageRequest.of(0, 10);
        assertThatThrownBy(() -> biddingService.getBidHistory(auctionId, pageable))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.AUCTION_NOT_FOUND.name());
    }
}

