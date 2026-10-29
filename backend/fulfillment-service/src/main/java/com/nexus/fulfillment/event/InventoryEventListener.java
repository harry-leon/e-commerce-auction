package com.nexus.fulfillment.event;

import com.nexus.fulfillment.client.NotificationClient;
import com.nexus.fulfillment.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lắng nghe và xử lý các sự kiện vòng đời đơn hàng và thanh toán để đồng bộ kho (SRS §2.8 & §3.6.1)
 * đồng thời kết nối với Notification Service để gửi email giao dịch bắt buộc (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - OrderPaidEvent: Xác nhận trừ kho thực tế (Commit), chuyển thành OUTBOUND, phát sinh InventoryCommittedEvent
 *   và kích hoạt gửi email hóa đơn điện tử qua NotificationClient.
 * - OrderPaymentFailedEvent: Giải phóng tồn kho giữ tạm, hoàn trả về available_qty, phát sinh InventoryReleasedEvent
 *   và gửi email thông báo thanh toán thất bại kèm nhắc nhở thời hạn giữ kho 30 phút.
 * - OrderCancelledEvent: Giải phóng tồn kho giữ tạm khi đơn bị hủy, hoàn trả về available_qty.
 *
 * Nguyên tắc cô lập lỗi (§5.2 SRS):
 * - Thao tác kích hoạt thông báo qua NotificationClient được bọc trong khối try/catch an toàn;
 *   sự cố từ SMTP hay Notification Service không bao giờ làm rollback giao dịch trừ kho đã commit.
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryService inventoryService;
    private final NotificationClient notificationClient;

    public InventoryEventListener(InventoryService inventoryService) {
        this(inventoryService, null);
    }

    @Autowired
    public InventoryEventListener(InventoryService inventoryService,
                                  @Autowired(required = false) NotificationClient notificationClient) {
        this.inventoryService = inventoryService;
        this.notificationClient = notificationClient;
    }

    /**
     * Lắng nghe sự kiện OrderPaid: thực hiện trừ vĩnh viễn reserved_qty và total_qty.
     * Ghi bản ghi xuất kho thực tế vào sổ cái (OUTBOUND), phát sinh InventoryCommittedEvent
     * và gửi thông báo xác nhận thanh toán/hóa đơn điện tử qua Notification Service.
     */
    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        log.info("Nhận sự kiện OrderPaid cho orderId: {}, reservationId: {}", event.orderId(), event.reservationId());
        try {
            if (event.reservationId() != null) {
                inventoryService.confirmReservation(event.reservationId());
            } else if (event.orderId() != null) {
                inventoryService.commitByOrder(event.orderId());
            } else {
                log.warn("Sự kiện OrderPaid không chứa cả orderId lẫn reservationId, bỏ qua");
                return;
            }

            // Kích hoạt gửi email xác nhận thanh toán & hóa đơn (SRS §3.2.3 & §5.2)
            if (notificationClient != null && event.orderId() != null) {
                try {
                    notificationClient.sendOrderPaidNotification(event.orderId(), null);
                } catch (Exception ex) {
                    log.warn("Lỗi gửi thông báo OrderPaid qua Notification Service (§5.2 SRS: cô lập lỗi): {}", ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderPaid cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện OrderPaymentFailed: giảm reserved_qty và hoàn trả về available_qty.
     * Ghi sổ cái RELEASE, phát sinh InventoryReleasedEvent và gửi email cảnh báo tới người mua.
     */
    @EventListener
    public void onOrderPaymentFailed(OrderPaymentFailedEvent event) {
        log.info("Nhận sự kiện OrderPaymentFailed cho orderId: {}, lý do: {}", event.orderId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "PAYMENT_FAILED";
            if (event.reservationId() != null) {
                inventoryService.releaseReservation(event.reservationId(), reason);
            } else if (event.orderId() != null) {
                inventoryService.releaseByOrder(event.orderId(), reason);
            } else {
                log.warn("Sự kiện OrderPaymentFailed không chứa cả orderId lẫn reservationId, bỏ qua");
                return;
            }

            // Kích hoạt gửi email thông báo thanh toán thất bại (SRS §3.2.3 & §5.2)
            if (notificationClient != null && event.orderId() != null) {
                try {
                    notificationClient.sendPaymentFailedNotification(event.orderId(), null, reason);
                } catch (Exception ex) {
                    log.warn("Lỗi gửi thông báo OrderPaymentFailed qua Notification Service (§5.2 SRS: cô lập lỗi): {}", ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderPaymentFailed cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện OrderCancelled: giảm reserved_qty và hoàn trả về available_qty.
     * Ghi sổ cái RELEASE và phát sinh InventoryReleasedEvent.
     */
    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Nhận sự kiện OrderCancelled cho orderId: {}, lý do: {}", event.orderId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "ORDER_CANCELLED";
            if (event.reservationId() != null) {
                inventoryService.releaseReservation(event.reservationId(), reason);
            } else if (event.orderId() != null) {
                inventoryService.releaseByOrder(event.orderId(), reason);
            } else {
                log.warn("Sự kiện OrderCancelled không chứa cả orderId lẫn reservationId, bỏ qua");
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderCancelled cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }
}
