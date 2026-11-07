package com.nexus.auction.service;

import com.nexus.auction.config.AuctionProperties;
import com.nexus.auction.dto.request.CancelAuctionRequest;
import com.nexus.auction.dto.request.CreateAuctionRequest;
import com.nexus.auction.dto.request.UpdateAuctionRequest;
import com.nexus.auction.dto.response.AuctionResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.service.impl.AuctionServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit Test cho AuctionServiceImpl.
 * Kiểm tra các kịch bản tạo, truy vấn, cập nhật và hủy phiên đấu giá.
 */
@ExtendWith(MockitoExtension.class)
class AuctionServiceTest {

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionProperties auctionProperties;

    @InjectMocks
    private AuctionServiceImpl auctionService;

    private UUID sellerId;
    private UUID productId;
    private UUID skuId;
    private AuctionProperties.Bidding biddingConfig;
    private AuctionProperties.Rules rulesConfig;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        skuId = UUID.randomUUID();

        biddingConfig = new AuctionProperties.Bidding();
        biddingConfig.setMinIncrement(1000L);
        biddingConfig.setAntiSnipingSeconds(120);

        rulesConfig = new AuctionProperties.Rules();
        rulesConfig.setMaxActiveAuctionsPerSeller(20);
    }

    private void mockProperties() {
        when(auctionProperties.getBidding()).thenReturn(biddingConfig);
        when(auctionProperties.getRules()).thenReturn(rulesConfig);
    }

    @Nested
    @DisplayName("Nghiệp vụ Tạo phiên đấu giá (createAuction)")
    class CreateAuctionTests {

        @Test
        @DisplayName("Tạo phiên thành công khi dữ liệu hợp lệ")
        void createAuction_Success() {
            mockProperties();

            Instant now = Instant.now();
            Instant startTime = now.plus(1, ChronoUnit.HOURS);
            Instant endTime = startTime.plus(2, ChronoUnit.HOURS);

            CreateAuctionRequest request = new CreateAuctionRequest();
            request.setProductId(productId);
            request.setSkuId(skuId);
            request.setStartPrice(new BigDecimal("5000000.00"));
            request.setBidIncrement(new BigDecimal("100000.00"));
            request.setStartTime(startTime);
            request.setEndTime(endTime);
            request.setVisibility(AuctionVisibility.PUBLIC);

            when(auctionRepository.countBySellerIdAndStatusIn(eq(sellerId), anyCollection())).thenReturn(3L);
            when(auctionRepository.existsByProductIdAndStatusIn(eq(productId), anyCollection())).thenReturn(false);
            when(auctionRepository.existsBySkuIdAndStatusIn(eq(skuId), anyCollection())).thenReturn(false);

            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            AuctionResponse response = auctionService.createAuction(sellerId, request);

            assertThat(response).isNotNull();
            assertThat(response.getProductId()).isEqualTo(productId);
            assertThat(response.getSkuId()).isEqualTo(skuId);
            assertThat(response.getSellerId()).isEqualTo(sellerId);
            assertThat(response.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
            assertThat(response.getStartPrice()).isEqualByComparingTo("5000000.00");
            assertThat(response.getCurrentPrice()).isEqualByComparingTo("5000000.00");
            assertThat(response.getBidIncrement()).isEqualByComparingTo("100000.00");

            ArgumentCaptor<Auction> captor = ArgumentCaptor.forClass(Auction.class);
            verify(auctionRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        }

        @Test
        @DisplayName("Từ chối tạo phiên khi bước giá nhỏ hơn cấu hình tối thiểu (1,000 VND)")
        void createAuction_ThrowsException_WhenBidIncrementTooLow() {
            mockProperties();

            Instant now = Instant.now();
            CreateAuctionRequest request = new CreateAuctionRequest();
            request.setProductId(productId);
            request.setStartPrice(new BigDecimal("100000.00"));
            request.setBidIncrement(new BigDecimal("500.00")); // nhỏ hơn 1000
            request.setStartTime(now.plus(1, ChronoUnit.HOURS));
            request.setEndTime(now.plus(3, ChronoUnit.HOURS));

            assertThatThrownBy(() -> auctionService.createAuction(sellerId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR.name()));
        }

        @Test
        @DisplayName("Từ chối tạo phiên khi thời lượng phiên nhỏ hơn 1 giờ")
        void createAuction_ThrowsException_WhenDurationTooShort() {
            when(auctionProperties.getRules()).thenReturn(rulesConfig);
            Instant now = Instant.now();
            CreateAuctionRequest request = new CreateAuctionRequest();
            request.setProductId(productId);
            request.setStartPrice(new BigDecimal("100000.00"));
            request.setBidIncrement(new BigDecimal("5000.00"));
            request.setStartTime(now.plus(1, ChronoUnit.HOURS));
            request.setEndTime(now.plus(1, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES)); // 30 phút

            assertThatThrownBy(() -> auctionService.createAuction(sellerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("tối thiểu phải từ 60 phút");
        }

        @Test
        @DisplayName("Từ chối tạo phiên khi người bán đã đạt hạn mức tối đa 20 phiên active")
        void createAuction_ThrowsException_WhenSellerLimitExceeded() {
            mockProperties();

            Instant now = Instant.now();
            CreateAuctionRequest request = new CreateAuctionRequest();
            request.setProductId(productId);
            request.setStartPrice(new BigDecimal("100000.00"));
            request.setBidIncrement(new BigDecimal("5000.00"));
            request.setStartTime(now.plus(1, ChronoUnit.HOURS));
            request.setEndTime(now.plus(3, ChronoUnit.HOURS));

            when(auctionRepository.countBySellerIdAndStatusIn(eq(sellerId), anyCollection())).thenReturn(20L);

            assertThatThrownBy(() -> auctionService.createAuction(sellerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("đạt giới hạn tối đa 20 phiên");
        }

        @Test
        @DisplayName("Từ chối tạo phiên khi sản phẩm đã có phiên đấu giá đang mở")
        void createAuction_ThrowsException_WhenProductAlreadyHasActiveAuction() {
            mockProperties();

            Instant now = Instant.now();
            CreateAuctionRequest request = new CreateAuctionRequest();
            request.setProductId(productId);
            request.setStartPrice(new BigDecimal("100000.00"));
            request.setBidIncrement(new BigDecimal("5000.00"));
            request.setStartTime(now.plus(1, ChronoUnit.HOURS));
            request.setEndTime(now.plus(3, ChronoUnit.HOURS));

            when(auctionRepository.countBySellerIdAndStatusIn(eq(sellerId), anyCollection())).thenReturn(2L);
            when(auctionRepository.existsByProductIdAndStatusIn(eq(productId), anyCollection())).thenReturn(true);

            assertThatThrownBy(() -> auctionService.createAuction(sellerId, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Sản phẩm này hiện đang có phiên đấu giá khác đang mở");
        }
    }

    @Nested
    @DisplayName("Nghiệp vụ Xem chi tiết và Danh sách phiên (getAuction)")
    class GetAuctionTests {

        @Test
        @DisplayName("Lấy chi tiết phiên đấu giá thành công")
        void getAuctionById_Success() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setProductId(productId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.SCHEDULED);
            auction.setStartPrice(new BigDecimal("100000.00"));

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

            AuctionResponse response = auctionService.getAuctionById(auctionId);

            assertThat(response).isNotNull();
            assertThat(response.getId()).isEqualTo(auctionId);
            assertThat(response.getStatus()).isEqualTo(AuctionStatus.SCHEDULED);
        }

        @Test
        @DisplayName("Ném ResourceNotFoundException khi ID phiên không tồn tại")
        void getAuctionById_NotFound() {
            UUID auctionId = UUID.randomUUID();
            when(auctionRepository.findById(auctionId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> auctionService.getAuctionById(auctionId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .satisfies(ex -> assertThat(((ResourceNotFoundException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_NOT_FOUND.name()));
        }

        @Test
        @DisplayName("Tra cứu danh sách phiên đấu giá có phân trang")
        void getAuctions_Success() {
            Auction auction = new Auction();
            auction.setId(UUID.randomUUID());
            auction.setStatus(AuctionStatus.ACTIVE);

            Page<Auction> page = new PageImpl<>(Collections.singletonList(auction));
            when(auctionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

            Page<AuctionResponse> result = auctionService.getAuctions(AuctionStatus.ACTIVE, null, null, PageRequest.of(0, 10));

            assertThat(result).isNotNull();
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getStatus()).isEqualTo(AuctionStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("Nghiệp vụ Cập nhật phiên trước giờ mở (updateAuction)")
    class UpdateAuctionTests {

        @Test
        @DisplayName("Cập nhật thành công khi người gọi là chủ phiên và phiên chưa mở")
        void updateAuction_Success() {
            UUID auctionId = UUID.randomUUID();
            Instant now = Instant.now();
            Instant start = now.plus(2, ChronoUnit.HOURS);
            Instant end = start.plus(2, ChronoUnit.HOURS);

            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.SCHEDULED);
            auction.setStartTime(start);
            auction.setEndTime(end);
            auction.setStartPrice(new BigDecimal("100000.00"));

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UpdateAuctionRequest request = new UpdateAuctionRequest();
            request.setStartPrice(new BigDecimal("150000.00"));

            AuctionResponse response = auctionService.updateAuction(auctionId, sellerId, false, request);

            assertThat(response).isNotNull();
            assertThat(response.getStartPrice()).isEqualByComparingTo("150000.00");
        }

        @Test
        @DisplayName("Từ chối cập nhật khi người gọi không phải chủ sở hữu (IDOR)")
        void updateAuction_Forbidden_WhenNotOwner() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

            UUID otherUser = UUID.randomUUID();
            UpdateAuctionRequest request = new UpdateAuctionRequest();

            assertThatThrownBy(() -> auctionService.updateAuction(auctionId, otherUser, false, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUTH_FORBIDDEN.name()));
        }

        @Test
        @DisplayName("Từ chối cập nhật khi phiên đã bắt đầu")
        void updateAuction_ThrowsException_WhenAlreadyStarted() {
            UUID auctionId = UUID.randomUUID();
            Instant now = Instant.now();

            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.ACTIVE);
            auction.setStartTime(now.minus(1, ChronoUnit.HOURS)); // đã bắt đầu

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

            UpdateAuctionRequest request = new UpdateAuctionRequest();

            assertThatThrownBy(() -> auctionService.updateAuction(auctionId, sellerId, false, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED.name()));
        }
    }

    @Nested
    @DisplayName("Nghiệp vụ Hủy phiên đấu giá (cancelAuction)")
    class CancelAuctionTests {

        @Test
        @DisplayName("Seller hủy phiên đấu giá trước giờ mở thành công")
        void cancelAuction_Success_BySeller() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.SCHEDULED);
            auction.setStartTime(Instant.now().plus(Duration.ofHours(2)));

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            CancelAuctionRequest request = new CancelAuctionRequest("Hết hàng trong kho");
            AuctionResponse response = auctionService.cancelAuction(auctionId, sellerId, false, request);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        }

        @Test
        @DisplayName("Seller bị từ chối hủy phiên khi phiên đang ACTIVE hoặc đã đến giờ bắt đầu")
        void cancelAuction_ThrowsException_WhenSellerCancelsActiveAuction() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.ACTIVE);
            auction.setStartTime(Instant.now().minus(Duration.ofMinutes(10)));

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

            CancelAuctionRequest request = new CancelAuctionRequest("Seller đổi ý muốn hủy phiên đang chạy");

            assertThatThrownBy(() -> auctionService.cancelAuction(auctionId, sellerId, false, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Người bán chỉ có thể hủy phiên đấu giá trước giờ mở phiên")
                    .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(ErrorCode.AUCTION_ALREADY_STARTED.name()));
        }

        @Test
        @DisplayName("Admin có quyền cưỡng chế hủy phiên vi phạm kể cả khi phiên đang ACTIVE")
        void cancelAuction_Success_ByAdmin() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.ACTIVE);

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
            when(auctionRepository.save(any(Auction.class))).thenAnswer(invocation -> invocation.getArgument(0));

            UUID adminId = UUID.randomUUID();
            CancelAuctionRequest request = new CancelAuctionRequest("Admin can thiệp khẩn cấp");
            AuctionResponse response = auctionService.cancelAuction(auctionId, adminId, true, request);

            assertThat(response).isNotNull();
            assertThat(response.getStatus()).isEqualTo(AuctionStatus.CANCELLED);
        }

        @Test
        @DisplayName("Từ chối hủy phiên khi phiên đã kết thúc (ENDED) hoặc đã quyết toán (SETTLED)")
        void cancelAuction_ThrowsException_WhenAlreadyEnded() {
            UUID auctionId = UUID.randomUUID();
            Auction auction = new Auction();
            auction.setId(auctionId);
            auction.setSellerId(sellerId);
            auction.setStatus(AuctionStatus.SETTLED);

            when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

            CancelAuctionRequest request = new CancelAuctionRequest("Thử hủy phiên đã chốt");

            assertThatThrownBy(() -> auctionService.cancelAuction(auctionId, sellerId, false, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Không thể hủy phiên đấu giá đã kết thúc hoặc đã quyết toán");
        }
    }
}

