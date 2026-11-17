package com.nexus.auction.scheduler;

import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.service.AuctionOrderHandoverService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker chạy nền bền vững (Resilient Background Worker) tự động quét và thử lại bàn giao các phiên đấu giá chưa tạo đơn hàng.
 *
 * Chức năng:
 * - Khắc phục điểm yếu khi bàn giao trực tiếp thất bại do sự cố mạng kéo dài hoặc service restart (Feedback Point 1).
 * - Định kỳ quét các bản ghi trong auction_settlements có result = 'SUCCESS' và order_id IS NULL.
 * - Gọi AuctionOrderHandoverService thực hiện bàn giao sang Commerce Service và chuyển tiếp kho tại Fulfillment Service.
 * - Cô lập lỗi từng phiên đấu giá: nếu một phiên gặp sự cố (ví dụ mạng chập chờn), các phiên khác vẫn tiếp tục được xử lý.
 *
 * Quy tắc và lý do:
 * - Tự động phục hồi sau restart: ngay khi service khởi động lại, sau initialDelay worker sẽ quét lại toàn bộ settlement tồn đọng.
 * - Kết hợp tính lũy nghiệm (Idempotency) của Commerce và Fulfillment: đảm bảo dù worker có quét lại nhiều lần thì
 *   chỉ duy nhất 1 đơn hàng được tạo và tồn kho không bị reserve kép.
 * - Cấu hình chu kỳ qua 'app.auction.scheduler.handover-interval-ms' (mặc định 30 giây để tránh bắn request retry quá dồn dập).
 */
@Component
@ConditionalOnProperty(name = "app.auction.handover-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class AuctionOrderHandoverScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionOrderHandoverScheduler.class);

    private final AuctionSettlementRepository auctionSettlementRepository;
    private final AuctionOrderHandoverService auctionOrderHandoverService;

    public AuctionOrderHandoverScheduler(
            AuctionSettlementRepository auctionSettlementRepository,
            AuctionOrderHandoverService auctionOrderHandoverService) {
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.auctionOrderHandoverService = auctionOrderHandoverService;
    }

    /**
     * Tự động quét và bàn giao lại các phiên đấu giá thành công chưa có đơn hàng mỗi 30 giây.
     */
    @Scheduled(
            fixedDelayString = "${app.auction.scheduler.handover-interval-ms:30000}",
            initialDelay = 10000
    )
    public void retryPendingHandovers() {
        try {
            List<AuctionSettlement> pendingSettlements =
                    auctionSettlementRepository.findByResultAndOrderIdIsNull(SettlementResult.SUCCESS);

            if (pendingSettlements.isEmpty()) {
                return;
            }

            log.info("Phát hiện [{}] phiên đấu giá thành công chưa liên kết đơn hàng, bắt đầu tiến trình bàn giao nền",
                    pendingSettlements.size());

            int successCount = 0;
            for (AuctionSettlement settlement : pendingSettlements) {
                try {
                    log.info("Bàn giao nền cho phiên đấu giá [{}]", settlement.getAuctionId());
                    auctionOrderHandoverService.handoverAuctionOrder(settlement.getAuctionId());
                    successCount++;
                } catch (Exception ex) {
                    log.error("Bàn giao nền thất bại cho phiên đấu giá [{}], sẽ thử lại ở chu kỳ tiếp theo: {}",
                            settlement.getAuctionId(), ex.getMessage());
                }
            }

            log.info("Tiến trình bàn giao nền hoàn tất: [{}/{}] phiên được xử lý thành công",
                    successCount, pendingSettlements.size());
        } catch (Exception e) {
            log.error("Lỗi xảy ra trong quá trình chạy background handover scheduler: {}", e.getMessage(), e);
        }
    }
}
