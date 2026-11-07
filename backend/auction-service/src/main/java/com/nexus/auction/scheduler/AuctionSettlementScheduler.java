package com.nexus.auction.scheduler;

import com.nexus.auction.service.AuctionSettlementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker chạy nền tự động kiểm tra và chốt kết quả các phiên đấu giá đã quá hạn.
 *
 * @deprecated Từ HG-S2-4, các tác vụ đóng phiên và quyết toán được quản lý bởi {@link AuctionClosingScheduler}.
 *             Lớp này được giữ lại để đảm bảo tương thích mã nguồn và các bài test đơn vị cũ.
 */
@Deprecated
public class AuctionSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionSettlementScheduler.class);

    private final AuctionSettlementService auctionSettlementService;

    public AuctionSettlementScheduler(AuctionSettlementService auctionSettlementService) {
        this.auctionSettlementService = auctionSettlementService;
    }

    /**
     * Tự động quét và quyết toán định kỳ (giữ lại phục vụ tương thích unit test).
     */
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
