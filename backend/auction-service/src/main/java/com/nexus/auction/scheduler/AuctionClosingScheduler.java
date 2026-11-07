package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker chạy nền tự động đóng các phiên đấu giá khi đến hạn kết thúc (endTime <= now) và bàn giao quyết toán.
 *
 * Chức năng:
 * - Định kỳ quét các phiên có status = 'ACTIVE' và endTime <= now().
 * - Gọi AuctionLifecycleService để khóa phiên, xác định người chiến thắng và bàn giao quyết toán (Settlement Handoff).
 * - Bắt lỗi độc lập từng phiên để lỗi của một phiên không làm gián đoạn tiến trình lập lịch (Safe Retry).
 * - Cho phép bật/tắt linh hoạt qua biến cấu hình 'app.auction.scheduler.enabled'.
 */
@Component
@ConditionalOnProperty(name = "app.auction.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionClosingScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionClosingScheduler.class);

    private final AuctionLifecycleService auctionLifecycleService;

    public AuctionClosingScheduler(AuctionLifecycleService auctionLifecycleService) {
        this.auctionLifecycleService = auctionLifecycleService;
    }

    /**
     * Tự động quét và đóng phiên định kỳ mỗi 10 giây (initialDelay = 5 giây sau khi khởi động service).
     */
    @Scheduled(
            fixedDelayString = "${app.auction.scheduler.closing-interval-ms:${app.auction.scheduler.settlement-interval-ms:10000}}",
            initialDelay = 5000
    )
    public void runClosingJob() {
        try {
            int count = auctionLifecycleService.closeExpiredAuctions();
            if (count > 0) {
                log.info("Scheduler đóng phiên và quyết toán đã xử lý thành công [{}] phiên đấu giá", count);
            }
        } catch (Exception e) {
            log.error("Lỗi xảy ra trong quá trình chạy background closing job: {}", e.getMessage(), e);
        }
    }
}

