package com.nexus.auction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.auction.client.ReputationEligibilityClient;
import com.nexus.auction.config.AuctionProperties;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.repository.BidRepository;
import com.nexus.auction.service.impl.AuctionServiceImpl;
import com.nexus.auction.service.impl.BiddingServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Kiểm thử tích hợp điều kiện điểm uy tín khi tạo phiên (CREATE_AUCTION >= 50)
 * và đặt giá thầu (BID >= 40) trong auction-service (HG-S3-2).
 */
@ExtendWith(MockitoExtension.class)
class AuctionReputationEnforcementTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private AuctionSettlementRepository auctionSettlementRepository;

    @Mock
    private ReputationEligibilityClient reputationEligibilityClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AuctionProperties properties;
    private AuctionServiceImpl auctionService;
    private BiddingServiceImpl biddingService;
    private Instant fixedNow;

    @BeforeEach
    void setUp() {
        properties = new AuctionProperties();
        fixedNow = Instant.parse("2026-11-12T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedNow, ZoneId.of("UTC"));
        auctionService = new AuctionServiceImpl(auctionRepository, properties, reputationEligibilityClient);
        biddingService = new BiddingServiceImpl(
                auctionRepository,
                bidRepository,
                auctionSettlementRepository,
                properties,
                eventPublisher,
                fixedClock,
                reputationEligibilityClient
        );
    }

    @Test
    void createAuction_blocksSellerBelowMinReputationAndAllowsWhenDisabled() {
        UUID sellerId = UUID.randomUUID();
        CreateAuctionRequest request = new CreateAuctionRequest();
        request.setProductId(UUID.randomUUID());
        request.setSkuId(UUID.randomUUID());
        request.setStartPrice(new BigDecimal("1000000"));
        request.setBidIncrement(new BigDecimal("50000"));
        request.setStartTime(fixedNow.plus(Duration.ofDays(2)));
        request.setEndTime(fixedNow.plus(Duration.ofDays(3)));
        request.setVisibility(AuctionVisibility.PUBLIC);

        doThrow(new BusinessException(ErrorCode.REPUTATION_ACTION_FORBIDDEN, "Điểm uy tín dưới 50"))
                .when(reputationEligibilityClient).assertEligible(sellerId, "CREATE_AUCTION");

        assertThatThrownBy(() -> auctionService.createAuction(sellerId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REPUTATION_ACTION_FORBIDDEN.name());

        verify(auctionRepository, never()).save(any());

        properties.getRules().setReputationEnforcementEnabled(false);
        when(auctionRepository.countBySellerIdAndStatusIn(eq(sellerId), anyCollection())).thenReturn(0L);
        when(auctionRepository.existsByProductIdAndStatusIn(eq(request.getProductId()), anyCollection()))
                .thenReturn(false);
        when(auctionRepository.existsBySkuIdAndStatusIn(eq(request.getSkuId()), anyCollection())).thenReturn(false);
        when(auctionRepository.save(any(Auction.class))).thenAnswer(inv -> inv.getArgument(0));

        AuctionResponse created = auctionService.createAuction(sellerId, request);
        assertThat(created.getSellerId()).isEqualTo(sellerId);
    }

    @Test
    void placeBid_blocksBidderBelowMinReputationAndAllowsEligibleBidder() {
        UUID auctionId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID lowBidderId = UUID.randomUUID();
        Auction auction = new Auction();
        auction.setId(auctionId);
        auction.setSellerId(sellerId);
        auction.setProductId(UUID.randomUUID());
        auction.setStartPrice(new BigDecimal("1000000"));
        auction.setBidIncrement(new BigDecimal("50000"));
        auction.setCurrentPrice(new BigDecimal("1000000"));
        auction.setStatus(AuctionStatus.ACTIVE);
        auction.setStartTime(fixedNow.minus(Duration.ofMinutes(30)));
        auction.setEndTime(fixedNow.plus(Duration.ofHours(2)));
        auction.setExtensionCount(0);
        auction.setVersion(1);

        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(auctionSettlementRepository.existsByAuctionId(auctionId)).thenReturn(false);
        doThrow(new BusinessException(ErrorCode.REPUTATION_ACTION_FORBIDDEN, "Điểm uy tín dưới 40"))
                .when(reputationEligibilityClient).assertEligible(lowBidderId, "BID");

        assertThatThrownBy(() -> biddingService.placeBid(
                auctionId, lowBidderId, new PlaceBidRequest(new BigDecimal("1000000"), "bid-low-1")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REPUTATION_ACTION_FORBIDDEN.name());

        verify(bidRepository, never()).save(any());

        UUID goodBidderId = UUID.randomUUID();
        doNothing().when(reputationEligibilityClient).assertEligible(goodBidderId, "BID");
        when(bidRepository.countByAuctionId(auctionId)).thenReturn(1L);

        PlaceBidResult result = biddingService.placeBid(
                auctionId, goodBidderId, new PlaceBidRequest(new BigDecimal("1000000"), "bid-ok-1"));
        assertThat(result.getCurrentBid()).isEqualByComparingTo("1000000");
        verify(reputationEligibilityClient).assertEligible(goodBidderId, "BID");
    }
}
