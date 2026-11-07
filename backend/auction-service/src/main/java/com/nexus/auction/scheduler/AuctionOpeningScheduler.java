package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker chạy nền tự động mở các phiên đấu giá khi đến thời điểm bắt đầu (startTime <= now).
 *
 * Chức năng:
 * - Định kỳ quét các phiên có status = 'SCHEDULED' và startTime <= now().
 * - Gọi AuctionLifecycleService để chuyển trạng thái sang 'ACTIVE' và phát sự kiện AuctionStartedEvent.
 * - Tự phục hồi và thử lại an toàn (Safe Retry) nếu gặp lỗi kết nối hoặc xung đột khóa.
 * - Cho phép bật/tắt linh hoạt qua biến cấu hình 'app.auction.scheduler.enabled'.
 */
@Component
@ConditionalOnProperty(name = "app.auction.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionOpeningScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionOpeningScheduler.class);

    private final AuctionLifecycleService auctionLifecycleService;

    public AuctionOpeningScheduler(AuctionLifecycleService auctionLifecycleService) {
        this.auctionLifecycleService = auctionLifecycleService;
    }

    /**
     * Tự động quét và mở phiên đấu giá định kỳ mỗi 5 giây (initialDelay = 3 giây sau khi khởi động service).
     */
    @Scheduled(fixedDelayString = "${app.auction.scheduler.opening-interval-ms:5000}", initialDelay = 3000)
    public void runOpeningJob() {
        try {
            int count = auctionLifecycleService.openScheduledAuctions();
            if (count > 0) {
                log.info("Scheduler mở phiên đã kích hoạt thành công [{}] phiên đấu giá", count);
            }
        } catch (Exception e) {
            log.error("Lỗi xảy ra trong quá trình chạy background opening job: {}", e.getMessage(), e);
        }
    }
}

