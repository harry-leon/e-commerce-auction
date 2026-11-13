package com.nexus.fulfillment.event;

import com.nexus.fulfillment.service.ShippingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lắng nghe sự kiện OrderPaid để tự động kích hoạt tạo vận đơn idempotent (SRS §3.6.2, KH-S3-1).
 * Tách riêng khỏi InventoryEventListener để không làm thay đổi luồng trừ kho hiện có.
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
}
