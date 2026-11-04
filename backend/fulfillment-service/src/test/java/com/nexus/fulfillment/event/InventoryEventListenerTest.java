package com.nexus.fulfillment.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử cho bộ lắng nghe sự kiện InventoryEventListener (SRS §2.8 & §3.6.1).
 *
 * Tình huống kiểm thử:
 * 1. Tiếp nhận OrderPaidEvent (có reservationId): chuyển tiếp tới confirmReservation.
 * 2. Tiếp nhận OrderPaidEvent (chỉ có orderId): chuyển tiếp tới commitByOrder.
 * 3. Tiếp nhận OrderPaymentFailedEvent: chuyển tiếp tới releaseByOrder với lý do lỗi.
 * 4. Tiếp nhận OrderCancelledEvent: chuyển tiếp tới releaseByOrder với lý do hủy.
 * 5. Tiếp nhận AuctionCreatedEvent: chuyển tiếp tới reserveAuctionStock với thông tin phiên và TTL.
 * 6. Tiếp nhận AuctionExtendedEvent: chuyển tiếp tới extendAuctionReservation để kéo dài TTL (Anti-sniping).
 * 7. Tiếp nhận AuctionCancelledEvent: chuyển tiếp tới releaseByAuction với lý do hủy phiên.
 * 8. Tiếp nhận AuctionFailedEvent: chuyển tiếp tới releaseByAuction khi phiên không có người đặt giá.
 * 9. Tiếp nhận AuctionSettledEvent: chuyển tiếp tới transitionAuctionToOrder sang đơn hàng của người thắng.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventListenerTest {

    @Mock
    private InventoryService inventoryService;

    private InventoryEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventListener(inventoryService);
    }

    @Test
    @DisplayName("Xử lý OrderPaidEvent có reservationId: Gọi confirmReservation")
    void testOnOrderPaid_WithReservationId() {
        UUID orderId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        OrderPaidEvent event = OrderPaidEvent.of(orderId, resId);

        listener.onOrderPaid(event);

        verify(inventoryService, times(1)).confirmReservation(resId);
    }

    @Test
    @DisplayName("Xử lý OrderPaidEvent theo orderId: Gọi commitByOrder")
    void testOnOrderPaid_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderPaidEvent event = OrderPaidEvent.of(orderId);

        listener.onOrderPaid(event);

        verify(inventoryService, times(1)).commitByOrder(orderId);
    }

    @Test
    @DisplayName("Xử lý OrderPaymentFailedEvent theo orderId: Gọi releaseByOrder")
    void testOnOrderPaymentFailed_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderPaymentFailedEvent event = OrderPaymentFailedEvent.of(orderId, "GATEWAY_TIMEOUT");

        listener.onOrderPaymentFailed(event);

        verify(inventoryService, times(1)).releaseByOrder(orderId, "GATEWAY_TIMEOUT");
    }

    @Test
    @DisplayName("Xử lý OrderCancelledEvent theo orderId: Gọi releaseByOrder")
    void testOnOrderCancelled_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderCancelledEvent event = OrderCancelledEvent.of(orderId, "USER_REQUEST");

        listener.onOrderCancelled(event);

        verify(inventoryService, times(1)).releaseByOrder(orderId, "USER_REQUEST");
    }

    @Test
    @DisplayName("Xử lý AuctionCreatedEvent: Giữ kho với TTL bao quát toàn bộ phiên đấu giá (SRS §3.6.1)")
    void testOnAuctionCreated_Success() {
        UUID auctionId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(24, ChronoUnit.HOURS);

        AuctionCreatedEvent event = AuctionCreatedEvent.of(auctionId, skuId, warehouseId, startTime, endTime, 1);

        listener.onAuctionCreated(event);

        verify(inventoryService, times(1)).reserveAuctionStock(
                eq(auctionId),
                any(ReserveAuctionInventoryRequest.class)
        );
    }

    @Test
    @DisplayName("Xử lý AuctionExtendedEvent: Kéo dài TTL giữ kho khi có Anti-sniping (SRS §2.6)")
    void testOnAuctionExtended_Success() {
        UUID auctionId = UUID.randomUUID();
        Instant newEndTime = Instant.now().plus(2, ChronoUnit.HOURS);

        AuctionExtendedEvent event = AuctionExtendedEvent.of(auctionId, 5, newEndTime);

        listener.onAuctionExtended(event);

        verify(inventoryService, times(1)).extendAuctionReservation(
                eq(auctionId),
                any(ExtendAuctionReservationRequest.class)
        );
    }

    @Test
    @DisplayName("Xử lý AuctionCancelledEvent: Tức thời giải phóng tồn kho khi Seller/Admin hủy phiên")
    void testOnAuctionCancelled_Success() {
        UUID auctionId = UUID.randomUUID();
        AuctionCancelledEvent event = AuctionCancelledEvent.of(auctionId, "SELLER_CANCELLED");

        listener.onAuctionCancelled(event);

        verify(inventoryService, times(1)).releaseByAuction(eq(auctionId), eq("SELLER_CANCELLED"));
    }

    @Test
    @DisplayName("Xử lý AuctionCancelledEvent không truyền lý do: Áp dụng lý do mặc định AUCTION_CANCELLED")
    void testOnAuctionCancelled_DefaultReason() {
        UUID auctionId = UUID.randomUUID();
        AuctionCancelledEvent event = AuctionCancelledEvent.of(auctionId, null);

        listener.onAuctionCancelled(event);

        verify(inventoryService, times(1)).releaseByAuction(eq(auctionId), eq("AUCTION_CANCELLED"));
    }

    @Test
    @DisplayName("Xử lý AuctionFailedEvent: Giải phóng tồn kho khi phiên kết thúc không có người đặt giá")
    void testOnAuctionFailed_Success() {
        UUID auctionId = UUID.randomUUID();
        AuctionFailedEvent event = AuctionFailedEvent.of(auctionId, "NO_BIDS_PLACED");

        listener.onAuctionFailed(event);

        verify(inventoryService, times(1)).releaseByAuction(eq(auctionId), eq("NO_BIDS_PLACED"));
    }

    @Test
    @DisplayName("Xử lý AuctionSettledEvent: Chuyển tiếp giữ kho sang Đơn hàng của người thắng (SRS §3.6.1)")
    void testOnAuctionSettled_Success() {
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID winnerId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(24, ChronoUnit.HOURS);

        AuctionSettledEvent event = AuctionSettledEvent.of(auctionId, orderId, winnerId, deadline);

        listener.onAuctionSettled(event);

        verify(inventoryService, times(1)).transitionAuctionToOrder(
                eq(auctionId),
                any(TransitionAuctionToOrderRequest.class)
        );
    }
}
