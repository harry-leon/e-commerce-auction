package com.nexus.fulfillment.event;

import com.nexus.fulfillment.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lắng nghe và xử lý các sự kiện vòng đời đơn hàng và thanh toán để đồng bộ kho (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - OrderPaidEvent: Xác nhận trừ kho thực tế (Commit), chuyển thành OUTBOUND và phát sinh InventoryCommittedEvent.
 * - OrderPaymentFailedEvent: Giải phóng tồn kho giữ tạm, hoàn trả về available_qty và phát sinh InventoryReleasedEvent.
 * - OrderCancelledEvent: Giải phóng tồn kho giữ tạm khi đơn bị hủy, hoàn trả về available_qty và phát sinh InventoryReleasedEvent.
 *
 * Quy tắc Idempotency:
 * - Các phương thức xử lý kiểm tra trạng thái phiếu giữ hàng; nếu nhận trùng sự kiện hoặc retry nhiều lần
 *   thì tồn kho vẫn chỉ bị trừ hoặc hoàn lại đúng 1 lần duy nhất.
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryService inventoryService;

    public InventoryEventListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Lắng nghe sự kiện OrderPaid: thực hiện trừ vĩnh viễn reserved_qty và total_qty.
     * Ghi bản ghi xuất kho thực tế vào sổ cái (OUTBOUND) và phát sinh InventoryCommittedEvent.
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
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderPaid cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện OrderPaymentFailed: giảm reserved_qty và hoàn trả về available_qty.
     * Ghi sổ cái RELEASE và phát sinh InventoryReleasedEvent.
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
