package com.nexus.fulfillment.scheduler;

import com.nexus.fulfillment.service.ShippingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bộ lập lịch định kỳ polling đồng bộ hành trình các vận đơn đang hoạt động (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Chạy nền định kỳ (mặc định mỗi 60 giây) để quét các vận đơn có trạng thái active (CREATED, PENDING,
 *   PICKING, PACKED, PICKED_UP, SHIPPED, IN_TRANSIT).
 * - Gọi Carrier Adapter thông qua Circuit Breaker & Retry để đồng bộ trạng thái giao hàng mới nhất từ carrier.
 * - Cập nhật trạng thái idempotent và phát sinh domain events tương ứng khi có sự chuyển dịch trạng thái hợp lệ.
 *
 * Quy tắc và lý do:
 * - Phòng ngừa trường hợp webhook callback của carrier bị mất gói (dropped), chậm trễ hoặc đối tác không hỗ trợ webhook.
 * - Có thể bật/tắt hoặc điều chỉnh chu kỳ quét linh hoạt qua file cấu hình application.yml (app.fulfillment.shipping.polling-interval-ms).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        name = "app.fulfillment.shipping-polling-scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ShipmentPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ShipmentPollingScheduler.class);

    private final ShippingService shippingService;

    public ShipmentPollingScheduler(ShippingService shippingService) {
        this.shippingService = shippingService;
    }

    /**
     * Tác vụ định kỳ thăm dò trạng thái các vận đơn đang giao.
     * Chu kỳ quét lấy từ app.fulfillment.shipping.polling-interval-ms (mặc định 60.000ms = 1 phút).
     */
    @Scheduled(fixedDelayString = "${app.fulfillment.shipping.polling-interval-ms:60000}")
    public void pollActiveShipments() {
        try {
            int polledCount = shippingService.pollActiveShipments();
            if (polledCount > 0) {
                log.info("Bộ quét định kỳ đã cập nhật trạng thái mới cho {} vận đơn đang hoạt động", polledCount);
            }
        } catch (Exception ex) {
            log.error("Lỗi trong quá trình polling hành trình vận đơn định kỳ: {}", ex.getMessage(), ex);
        }
    }
}
