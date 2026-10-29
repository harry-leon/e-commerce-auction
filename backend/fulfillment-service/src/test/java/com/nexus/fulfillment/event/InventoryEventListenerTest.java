package com.nexus.fulfillment.event;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.fulfillment.client.NotificationClient;
import com.nexus.fulfillment.service.InventoryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Kiểm thử cho bộ lắng nghe sự kiện InventoryEventListener (SRS §2.8 & §3.6.1)
 * và kết nối Notification Service theo nguyên tắc cô lập lỗi (§5.2 SRS).
 *
 * Tình huống kiểm thử:
 * 1. Tiếp nhận OrderPaidEvent (có reservationId): chuyển tiếp tới confirmReservation và gửi email hóa đơn.
 * 2. Tiếp nhận OrderPaidEvent (chỉ có orderId): chuyển tiếp tới commitByOrder và gửi email hóa đơn.
 * 3. Tiếp nhận OrderPaymentFailedEvent: chuyển tiếp tới releaseByOrder và gửi cảnh báo thanh toán thất bại.
 * 4. Tiếp nhận OrderCancelledEvent: chuyển tiếp tới releaseByOrder với lý do hủy.
 * 5. Nguyên tắc cô lập lỗi (§5.2 SRS): Khi Notification Service sập/lỗi, commit kho vẫn thành công, không ném exception.
 */
@ExtendWith(MockitoExtension.class)
class InventoryEventListenerTest {

    @Mock
    private InventoryService inventoryService;

    @Mock
    private NotificationClient notificationClient;

    private InventoryEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventListener(inventoryService, notificationClient);
    }

    @Test
    @DisplayName("Xử lý OrderPaidEvent có reservationId: Gọi confirmReservation và gửi email hóa đơn")
    void testOnOrderPaid_WithReservationId() {
        UUID orderId = UUID.randomUUID();
        UUID resId = UUID.randomUUID();
        OrderPaidEvent event = OrderPaidEvent.of(orderId, resId);

        listener.onOrderPaid(event);

        verify(inventoryService, times(1)).confirmReservation(resId);
        verify(notificationClient, times(1)).sendOrderPaidNotification(eq(orderId), eq(null));
    }

    @Test
    @DisplayName("Xử lý OrderPaidEvent theo orderId: Gọi commitByOrder và gửi email hóa đơn")
    void testOnOrderPaid_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderPaidEvent event = OrderPaidEvent.of(orderId);

        listener.onOrderPaid(event);

        verify(inventoryService, times(1)).commitByOrder(orderId);
        verify(notificationClient, times(1)).sendOrderPaidNotification(eq(orderId), eq(null));
    }

    @Test
    @DisplayName("Xử lý OrderPaymentFailedEvent theo orderId: Gọi releaseByOrder và gửi cảnh báo thất bại")
    void testOnOrderPaymentFailed_WithOrderId() {
        UUID orderId = UUID.randomUUID();
        OrderPaymentFailedEvent event = OrderPaymentFailedEvent.of(orderId, "GATEWAY_TIMEOUT");

        listener.onOrderPaymentFailed(event);

        verify(inventoryService, times(1)).releaseByOrder(orderId, "GATEWAY_TIMEOUT");
        verify(notificationClient, times(1)).sendPaymentFailedNotification(eq(orderId), eq(null), eq("GATEWAY_TIMEOUT"));
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
    @DisplayName("Nguyên tắc cô lập lỗi (§5.2 SRS): Sự cố từ NotificationClient không làm hỏng tiến trình commit kho")
    void testFaultIsolation_NotificationFailureDoesNotAffectInventoryCommit() {
        UUID orderId = UUID.randomUUID();
        OrderPaidEvent event = OrderPaidEvent.of(orderId);

        // Giả lập NotificationClient ném exception do timeout/lỗi mạng
        doThrow(new RuntimeException("Notification service unreachable"))
                .when(notificationClient).sendOrderPaidNotification(any(UUID.class), any());

        // Tiến trình onOrderPaid phải bắt exception và hoàn tất an toàn
        assertDoesNotThrow(() -> listener.onOrderPaid(event));

        verify(inventoryService, times(1)).commitByOrder(orderId);
    }
}
