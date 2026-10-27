package com.nexus.fulfillment.scheduler;

import com.nexus.fulfillment.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bộ lập lịch tự động quét và giải phóng các phiếu giữ hàng tồn kho quá thời hạn 30 phút (SRS §3.6.1).
 *
 * Chức năng:
 * - Chạy nền định kỳ (mặc định mỗi 60 giây) để tìm kiếm các bản ghi InventoryReservation có trạng thái PENDING
 *   nhưng expires_at đã nhỏ hơn thời gian hiện tại.
 * - Tự động hoàn trả reserved_qty về cho available_qty và bắn sự kiện miền InventoryReservationExpiredEvent.
 *
 * Quy tắc và lý do:
 * - Đảm bảo hàng hóa không bị giữ vô hạn nếu người mua bỏ rơi giỏ hàng hoặc cổng thanh toán gặp sự cố mất kết nối.
 * - Có thể bật/tắt hoặc điều chỉnh chu kỳ quét linh hoạt qua file cấu hình application.yml.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
        name = "app.fulfillment.reservation-scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class InventoryReservationScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationScheduler.class);

    private final InventoryService inventoryService;

    public InventoryReservationScheduler(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Tác vụ định kỳ quét phiếu giữ kho quá hạn.
     * Mặc định chu kỳ quét lấy từ app.fulfillment.reservation-sweep-interval-ms (mặc định 60.000ms = 1 phút).
     */
    @Scheduled(fixedDelayString = "${app.fulfillment.reservation-sweep-interval-ms:60000}")
    public void sweepExpiredReservations() {
        try {
            int released = inventoryService.autoReleaseExpiredReservations();
            if (released > 0) {
                log.info("Bộ quét định kỳ đã giải phóng thành công {} phiếu giữ hàng quá hạn", released);
            }
        } catch (Exception ex) {
            log.error("Lỗi ngoài dự kiến trong chu kỳ quét giải phóng phiếu giữ kho quá hạn: {}", ex.getMessage(), ex);
        }
    }
}
