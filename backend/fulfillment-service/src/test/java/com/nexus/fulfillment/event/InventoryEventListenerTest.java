package com.nexus.fulfillment.event;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.fulfillment.service.InventoryService;
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
}
