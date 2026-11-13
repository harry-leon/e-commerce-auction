package com.nexus.fulfillment.event;

import com.nexus.fulfillment.service.ShippingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lắng nghe và xử lý các sự kiện vòng đời giao vận và thanh toán (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Tiếp nhận OrderPaidEvent để tự động kích hoạt tạo vận đơn idempotent theo quy tắc hãng và kho mặc định.
 * - Lắng nghe các sự kiện chuyển đổi trạng thái giao hàng (ShipmentPickedUp, ShipmentInTransit, ShipmentDelivered,
 *   ShipmentFailed, ShipmentReturned) phục vụ log giám sát và kiểm toán hệ thống.
 */
@Component
public class ShippingEventListener {

    private static final Logger log = LoggerFactory.getLogger(ShippingEventListener.class);

    private final ShippingService shippingService;

    public ShippingEventListener(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    /**
     * Khi nhận OrderPaidEvent, tự động tạo vận đơn cho orderId nếu đơn hàng chưa có vận đơn.
     * Lỗi tạo vận đơn được ghi log cảnh báo để không làm rollback giao dịch trừ kho đã hoàn tất trước đó.
     */
    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        if (event == null || event.orderId() == null) {
            return;
        }
        try {
            shippingService.createShipmentForPaidOrder(event.orderId());
        } catch (Exception ex) {
            log.warn("Không thể tự động tạo vận đơn cho OrderPaid orderId={}: {}",
                    event.orderId(), ex.getMessage());
        }
    }

    @EventListener
    public void onShipmentPickedUp(ShipmentPickedUpEvent event) {
        log.info("ShipmentPickedUpEvent: shipmentId={}, orderId={}, carrier={}, trackingNo={}",
                event.shipmentId(), event.orderId(), event.carrierCode(), event.trackingNumber());
    }

    @EventListener
    public void onShipmentInTransit(ShipmentInTransitEvent event) {
        log.info("ShipmentInTransitEvent: shipmentId={}, orderId={}, carrier={}, location={}",
                event.shipmentId(), event.orderId(), event.carrierCode(), event.currentLocation());
    }

    @EventListener
    public void onShipmentDelivered(ShipmentDeliveredEvent event) {
        log.info("ShipmentDeliveredEvent: shipmentId={}, orderId={}, carrier={}, deliveredAt={}",
                event.shipmentId(), event.orderId(), event.carrierCode(), event.deliveredAt());
    }

    @EventListener
    public void onShipmentFailed(ShipmentFailedEvent event) {
        log.warn("ShipmentFailedEvent: shipmentId={}, orderId={}, carrier={}, reason={}",
                event.shipmentId(), event.orderId(), event.carrierCode(), event.reason());
    }

    @EventListener
    public void onShipmentReturned(ShipmentReturnedEvent event) {
        log.warn("ShipmentReturnedEvent: shipmentId={}, orderId={}, carrier={}, reason={}",
                event.shipmentId(), event.orderId(), event.carrierCode(), event.reason());
    }
}
