package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.client.OrderVerificationClient;
import com.nexus.reputation.client.OrderVerificationClient.VerifiedOrder;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.entity.Rating;
import com.nexus.reputation.entity.Reputation;
import com.nexus.reputation.enums.TrustLevel;
import com.nexus.reputation.repository.PenaltyRepository;
import com.nexus.reputation.repository.RatingRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * Kiểm thử đơn vị cho luồng đánh giá hai chiều sau giao dịch (HG-S3-1).
 */
@ExtendWith(MockitoExtension.class)
class RatingServiceTest {

    @Mock
    private ReputationRepository reputationRepository;

    @Mock
    private PenaltyRepository penaltyRepository;

    @Mock
    private RatingRepository ratingRepository;

    @Mock
    private OrderVerificationClient orderVerificationClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReputationProperties properties;
    private ReputationServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new ReputationProperties();
        service = new ReputationServiceImpl(
                reputationRepository,
                penaltyRepository,
                ratingRepository,
                properties,
                orderVerificationClient,
                eventPublisher
        );
    }

    @Test
    @DisplayName("Buyer đánh giá Seller thành công khi đơn ở trạng thái DELIVERED")
    void createRating_success_buyerRatesSeller() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(false);
        when(orderVerificationClient.verifyOrder(orderId))
                .thenReturn(new VerifiedOrder(orderId, buyerId, sellerId, "DELIVERED"));
        when(reputationRepository.findByUserIdForUpdate(sellerId))
                .thenReturn(Optional.of(new Reputation(sellerId, 100, TrustLevel.STANDARD)));
        when(ratingRepository.saveAndFlush(any(Rating.class))).thenAnswer(inv -> inv.getArgument(0));

        RatingResponse response = service.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 5, "  Giao hàng nhanh  "));

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.raterId()).isEqualTo(buyerId);
        assertThat(response.rateeId()).isEqualTo(sellerId);
        assertThat(response.score()).isEqualTo(5);
        assertThat(response.comment()).isEqualTo("Giao hàng nhanh");
        assertThat(response.isDisputed()).isFalse();
    }

    @Test
    @DisplayName("Chặn tự đánh giá chính mình dù chưa gọi sang Commerce")
    void createRating_rejectsSelfRating() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        assertThatThrownBy(() -> service.createRating(userId, new CreateRatingRequest(orderId, userId, 5, "Tự khen")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name());

        verify(orderVerificationClient, never()).verifyOrder(any());
        verify(ratingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Chặn đánh giá trùng lần hai trên cùng (orderId, raterId)")
    void createRating_rejectsDuplicateRatingPerOrderPerRater() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(true);

        assertThatThrownBy(() -> service.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 4, "Lần 2")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS.name());

        verify(orderVerificationClient, never()).verifyOrder(any());
    }

    @Test
    @DisplayName("Chặn đánh giá nếu đơn hàng chưa ở trạng thái DELIVERED/COMPLETED")
    void createRating_rejectsWhenOrderNotDelivered() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(false);
        when(orderVerificationClient.verifyOrder(orderId))
                .thenReturn(new VerifiedOrder(orderId, buyerId, sellerId, "SHIPPING"));

        assertThatThrownBy(() -> service.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 5, "Sớm")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_ORDER_NOT_DELIVERED.name());

        verify(ratingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Chặn đánh giá nếu người gọi không phải bên mua/bán của đơn hàng hoặc chọn sai đối tác")
    void createRating_rejectsOutsiderOrWrongCounterparty() {
        UUID outsiderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(ratingRepository.existsByOrderIdAndRaterId(orderId, outsiderId)).thenReturn(false);
        when(orderVerificationClient.verifyOrder(orderId))
                .thenReturn(new VerifiedOrder(orderId, buyerId, sellerId, "COMPLETED"));

        assertThatThrownBy(() -> service.createRating(
                outsiderId, new CreateRatingRequest(orderId, sellerId, 1, "Phá")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_NOT_OWNER.name());

        when(ratingRepository.existsByOrderIdAndRaterId(orderId, buyerId)).thenReturn(false);
        assertThatThrownBy(() -> service.createRating(
                buyerId, new CreateRatingRequest(orderId, outsiderId, 4, "Sai người")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name());
    }

    @Test
    @DisplayName("Tra cứu danh sách đánh giá theo user và theo order")
    void queryRatingsByUserAndOrder() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Rating rating = new Rating(
                UUID.randomUUID(), buyerId, sellerId, orderId, 5, "Rất tốt", false, false, Instant.now());

        when(ratingRepository.findByRateeIdAndDisputedFalseOrderByCreatedAtDesc(sellerId, PageRequest.of(0, 10)))
                .thenReturn(new PageImpl<>(List.of(rating)));
        when(ratingRepository.findByOrderIdOrderByCreatedAtDesc(orderId))
                .thenReturn(List.of(rating));

        Page<RatingResponse> userRatings = service.getRatingsForUser(sellerId, PageRequest.of(0, 10));
        List<RatingResponse> orderRatings = service.getRatingsForOrder(orderId);

        assertThat(userRatings.getTotalElements()).isEqualTo(1);
        assertThat(orderRatings).hasSize(1);
        assertThat(orderRatings.get(0).comment()).isEqualTo("Rất tốt");
    }
}
