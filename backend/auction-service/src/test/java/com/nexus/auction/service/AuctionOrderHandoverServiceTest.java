package com.nexus.auction.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.auction.client.CommerceOrderClient;
import com.nexus.auction.client.FulfillmentInventoryClient;
import com.nexus.auction.client.dto.AuctionOrderResult;
import com.nexus.auction.client.dto.CreateAuctionOrderPayload;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.event.AuctionSettledEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.service.impl.AuctionOrderHandoverServiceImpl;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Bộ kiểm thử đơn vị cho AuctionOrderHandoverService (DoD KH-S2-2 / SRS §3.5.3).
 *
 * Chức năng:
 * - Tiếp nhận kết quả chốt phiên (Consume Settlement): Xác thực toàn vẹn dữ liệu (winner != seller, final_price > 0).
 * - Bàn giao khởi tạo đơn hàng sang Commerce Service (Order Creation Handover).
 * - Chuyển tiếp phiếu giữ kho AUCTION -> ORDER thực tế tại Fulfillment Service (DoD KH-S2-1).
 * - Gắn các reservationId vào Order Commerce để PaymentService kiểm tra trước thanh toán.
 * - Đảm bảo tính lũy nghiệm (Idempotency): Nếu đơn hàng đã tồn tại (order_id != null), không gọi sang Commerce nữa.
 * - Phát sinh domain event AuctionSettledEvent sau khi đơn hàng được tạo thành công.
 *
 * Quy chuẩn: Tuân thủ Clean Architecture, JUnit 5, Mockito và quy định AGENTS.md.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuctionOrderHandoverService Unit Test Suite - Tích hợp quyết toán & bàn giao đơn hàng")
class AuctionOrderHandoverServiceTest {

    @Mock
    private AuctionSettlementRepository auctionSettlementRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private CommerceOrderClient commerceOrderClient;

    @Mock
    private FulfillmentInventoryClient fulfillmentInventoryClient;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<CreateAuctionOrderPayload> payloadCaptor;

    @Captor
    private ArgumentCaptor<AuctionSettledEvent> eventCaptor;

    private AuctionOrderHandoverService handoverService;

    private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private final UUID winnerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID productId = UUID.fromString("00000000-0000-0000-0000-000000000033");
    private final UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000044");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000055");
    private final UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private final BigDecimal finalPrice = new BigDecimal("1200000.00");
    private final Instant now = Instant.parse("2026-11-09T10:00:00Z");

    @BeforeEach
    void setUp() {
        handoverService = new AuctionOrderHandoverServiceImpl(
                auctionRepository,
                auctionSettlementRepository,
                commerceOrderClient,
                fulfillmentInventoryClient,
                eventPublisher
        );
    }

    private Auction buildAuction() {
        Auction auction = new Auction();
        auction.setId(auctionId);
        auction.setSellerId(sellerId);
        auction.setProductId(productId);
        auction.setSkuId(skuId);
        auction.setStatus(AuctionStatus.SETTLED);
        return auction;
    }

    private AuctionSettlement buildSettlement(SettlementResult result, UUID wId, BigDecimal price, UUID existingOrderId) {
        AuctionSettlement settlement = new AuctionSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setAuctionId(auctionId);
        settlement.setWinnerId(wId);
        settlement.setFinalPrice(price);
        settlement.setResult(result);
        settlement.setSettledAt(now);
        settlement.setOrderId(existingOrderId);
        return settlement;
    }

    @Test
    @DisplayName("Bàn giao tạo đơn hàng thành công: Gọi Commerce, cập nhật orderId và phát sinh AuctionSettledEvent")
    void test_handoverAuctionOrder_success() {
        // Chuẩn bị dữ liệu
        Auction auction = buildAuction();
        AuctionSettlement settlement = buildSettlement(SettlementResult.SUCCESS, winnerId, finalPrice, null);
        Instant deadline = now.plus(24, ChronoUnit.HOURS);
        AuctionOrderResult orderResult = new AuctionOrderResult(
                orderId, "ORD-AUC-001", auctionId, winnerId, sellerId, "PENDING_PAYMENT", finalPrice, deadline
        );

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));
        when(commerceOrderClient.createAuctionOrder(any(CreateAuctionOrderPayload.class))).thenReturn(orderResult);
        when(fulfillmentInventoryClient.transitionAuctionToOrder(eq(auctionId), eq(orderId), any()))
                .thenReturn(List.of(reservationId));
        when(auctionSettlementRepository.saveAndFlush(any(AuctionSettlement.class))).thenAnswer(i -> i.getArgument(0));

        // Thực thi
        AuctionSettlementResponse response = handoverService.handoverAuctionOrder(auctionId);

        // Kiểm tra kết quả
        assertThat(response).isNotNull();
        assertThat(response.getAuctionId()).isEqualTo(auctionId);
        assertThat(response.getOrderId()).isEqualTo(orderId);
        assertThat(response.getResult()).isEqualTo(SettlementResult.SUCCESS);

        // Xác minh payload gửi sang Commerce Service
        verify(commerceOrderClient).createAuctionOrder(payloadCaptor.capture());
        CreateAuctionOrderPayload payload = payloadCaptor.getValue();
        assertThat(payload.auctionId()).isEqualTo(auctionId);
        assertThat(payload.winnerId()).isEqualTo(winnerId);
        assertThat(payload.sellerId()).isEqualTo(sellerId);
        assertThat(payload.skuId()).isEqualTo(skuId);
        assertThat(payload.finalPrice()).isEqualByComparingTo(finalPrice);

        // Xác minh gọi Fulfillment Service chuyển tiếp kho thực tế
        verify(fulfillmentInventoryClient).transitionAuctionToOrder(eq(auctionId), eq(orderId), any());

        // Xác minh gọi Commerce Service gắn reservationIds vào đơn hàng
        verify(commerceOrderClient).attachOrderReservations(eq(orderId), eq(List.of(reservationId)));

        // Xác minh bản ghi settlement được cập nhật order_id và saveAndFlush
        assertThat(settlement.getOrderId()).isEqualTo(orderId);
        verify(auctionSettlementRepository).saveAndFlush(settlement);

        // Xác minh domain event AuctionSettledEvent được phát sinh
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        AuctionSettledEvent event = eventCaptor.getValue();
        assertThat(event.auctionId()).isEqualTo(auctionId);
        assertThat(event.orderId()).isEqualTo(orderId);
        assertThat(event.winnerId()).isEqualTo(winnerId);
        assertThat(event.paymentDeadline()).isEqualTo(deadline);
    }

    @Test
    @DisplayName("Tính lũy nghiệm: Nếu bản ghi settlement đã có order_id thì không gọi Commerce tạo lại")
    void test_handoverAuctionOrder_idempotent_alreadyHasOrderId() {
        AuctionSettlement existingSettlement = buildSettlement(SettlementResult.SUCCESS, winnerId, finalPrice, orderId);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(existingSettlement));

        AuctionSettlementResponse response = handoverService.handoverAuctionOrder(auctionId);

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(orderId);

        // Tuyệt đối không gọi Commerce client và không lưu lại DB
        verify(commerceOrderClient, never()).createAuctionOrder(any());
        verify(auctionRepository, never()).findById(any());
        verify(auctionSettlementRepository, never()).save(any());
    }

    @Test
    @DisplayName("Từ chối bàn giao khi kết quả phiên không phải SUCCESS (ví dụ FAILED)")
    void test_handoverAuctionOrder_failedSettlement_throwsException() {
        AuctionSettlement settlement = buildSettlement(SettlementResult.FAILED, null, null, null);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));

        assertThatThrownBy(() -> handoverService.handoverAuctionOrder(auctionId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AUCTION_NOT_ACTIVE.name());

        verify(commerceOrderClient, never()).createAuctionOrder(any());
    }

    @Test
    @DisplayName("Từ chối bàn giao khi thiếu thông tin người thắng cuộc")
    void test_handoverAuctionOrder_missingWinner_throwsException() {
        AuctionSettlement settlement = buildSettlement(SettlementResult.SUCCESS, null, finalPrice, null);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));

        assertThatThrownBy(() -> handoverService.handoverAuctionOrder(auctionId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name());

        verify(commerceOrderClient, never()).createAuctionOrder(any());
    }

    @Test
    @DisplayName("Từ chối bàn giao khi giá thắng cuộc nhỏ hơn hoặc bằng 0")
    void test_handoverAuctionOrder_invalidPrice_throwsException() {
        AuctionSettlement settlement = buildSettlement(SettlementResult.SUCCESS, winnerId, BigDecimal.ZERO, null);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));

        assertThatThrownBy(() -> handoverService.handoverAuctionOrder(auctionId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INVALID_PARAM_VALUE.name());

        verify(commerceOrderClient, never()).createAuctionOrder(any());
    }

    @Test
    @DisplayName("Từ chối bàn giao khi người thắng trùng với người bán (vi phạm quy tắc nghiệp vụ)")
    void test_handoverAuctionOrder_winnerIsSeller_throwsException() {
        Auction auction = buildAuction();
        auction.setSellerId(winnerId); // Người bán trùng người thắng
        AuctionSettlement settlement = buildSettlement(SettlementResult.SUCCESS, winnerId, finalPrice, null);

        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(settlement));
        when(auctionRepository.findById(auctionId)).thenReturn(Optional.of(auction));

        assertThatThrownBy(() -> handoverService.handoverAuctionOrder(auctionId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.AUCTION_SELLER_CANNOT_BID.name());

        verify(commerceOrderClient, never()).createAuctionOrder(any());
    }

    @Test
    @DisplayName("Ném ResourceNotFoundException khi không tìm thấy bản ghi quyết toán của phiên")
    void test_handoverAuctionOrder_notFound_throwsException() {
        when(auctionSettlementRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handoverService.handoverAuctionOrder(auctionId))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(commerceOrderClient, never()).createAuctionOrder(any());
    }
}
