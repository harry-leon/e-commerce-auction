package com.nexus.reputation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.reputation.config.ReputationProperties;
import com.nexus.reputation.dto.CreateRatingRequest;
import com.nexus.reputation.dto.RatingResponse;
import com.nexus.reputation.repository.RatingRepository;
import com.nexus.reputation.service.impl.ReputationServiceImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Kiểm thử tầng JPA trên H2 cho bảng ratings: ràng buộc duy nhất uq_order_rater,
 * tự khởi tạo reputations cho rateeId và lọc bản ghi không bị khiếu nại.
 */
@DataJpaTest(properties = "app.reputation.order-verification-enabled=false")
@ActiveProfiles("test")
@Import({ReputationServiceImpl.class, RatingRepositoryJpaTest.Config.class})
class RatingRepositoryJpaTest {

    @Autowired
    private ReputationService reputationService;

    @Autowired
    private RatingRepository ratingRepository;

    @TestConfiguration
    static class Config {
        @Bean
        ReputationProperties reputationProperties() {
            return new ReputationProperties();
        }
    }

    @Test
    void createRating_persistsTwoWayRatingsAndBlocksDuplicate() {
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        RatingResponse buyerToSeller = reputationService.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 5, "Sản phẩm đúng mô tả"));
        RatingResponse sellerToBuyer = reputationService.createRating(
                sellerId, new CreateRatingRequest(orderId, buyerId, 4, "Thanh toán đúng hẹn"));

        assertThat(buyerToSeller.id()).isNotNull();
        assertThat(sellerToBuyer.id()).isNotNull();

        assertThatThrownBy(() -> reputationService.createRating(
                buyerId, new CreateRatingRequest(orderId, sellerId, 3, "Gửi lại")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS.name());

        Page<RatingResponse> sellerRatings = reputationService.getRatingsForUser(sellerId, PageRequest.of(0, 10));
        assertThat(sellerRatings.getTotalElements()).isEqualTo(1);
        assertThat(ratingRepository.countByRateeIdAndDisputedFalse(sellerId)).isEqualTo(1L);
        assertThat(ratingRepository.findAverageScoreByRateeId(sellerId)).isEqualTo(5.0d);
    }
}
