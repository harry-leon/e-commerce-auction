package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker chạy nền tự động kiểm tra và chốt kết quả các phiên đấu giá đã quá hạn.
 *
 * Chức năng:
 * - Định kỳ quét các phiên có status = 'ACTIVE' và endTime <= now().
 * - Gọi AuctionSettlementService để khóa phiên và xác định người chiến thắng.
 * - Cho phép bật/tắt linh hoạt qua biến cấu hình 'app.auction.scheduler.enabled'.
 */
@Component
@ConditionalOnProperty(name = "app.auction.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionSettlementScheduler.class);

    private final AuctionSettlementService auctionSettlementService;

    public AuctionSettlementScheduler(AuctionSettlementService auctionSettlementService) {
        this.auctionSettlementService = auctionSettlementService;
    }

    /**
     * Tự động quét và quyết toán định kỳ mỗi 10 giây (initialDelay = 5 giây sau khi khởi động service).
     */
    @Scheduled(fixedDelayString = "${app.auction.scheduler.settlement-interval-ms:10000}", initialDelay = 5000)
    public void runSettlementJob() {
        try {
            int count = auctionSettlementService.settleExpiredAuctions();
            if (count > 0) {
                log.info("Scheduler quyết toán đã xử lý thành công [{}] phiên đấu giá", count);
            }
        } catch (Exception e) {
            log.error("Lỗi xảy ra trong quá trình chạy background settlement job: {}", e.getMessage(), e);
        }
    }
}

