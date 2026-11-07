package com.nexus.auction.service.impl;

import com.nexus.auction.entity.Auction;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.event.AuctionStartedEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.service.AuctionLifecycleService;
import com.nexus.auction.service.AuctionSettlementService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt nghiệp vụ quản lý vòng đời phiên đấu giá và điều phối chuyển đổi trạng thái (HG-S2-4).
 *
 * Chức năng:
 * 1. Tự động chuyển đổi trạng thái phiên đấu giá từ SCHEDULED sang ACTIVE khi đến thời điểm mở (startTime <= now).
 * 2. Tự động đóng phiên đấu giá khi đến hạn kết thúc (endTime <= now) và chuyển giao quyết toán (Settlement Handoff).
 * 3. Kiểm tra toàn vẹn trạng thái phiên trước mọi bước chuyển tiếp (Status Validation).
 * 4. Ngăn chặn thực thi trùng lặp (Duplicate Lifecycle Execution Prevention) nhờ khóa bi quan findByIdForUpdate.
 * 5. Bắt lỗi độc lập từng phiên trong các tác vụ hàng loạt, hỗ trợ cơ chế phục hồi và thử lại an toàn (Safe Retry).
 * 6. Phát sự kiện miền AuctionStartedEvent sau khi giao dịch cơ sở dữ liệu đã commit thành công.
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5 và BR-AUC-06/07.
 * - Khóa bi quan findByIdForUpdate ngăn chặn triệt để race condition khi có nhiều tiến trình scheduler cùng chạy.
 * - Tách biệt transaction độc lập qua self-proxy để một phiên lỗi không làm rollback toàn bộ lô phiên cần mở.
 * - Dùng Clock để dễ dàng kiểm thử các mốc thời gian khác nhau mà không cần sleep.
 */
@Service
public class AuctionLifecycleServiceImpl implements AuctionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AuctionLifecycleServiceImpl.class);

    private final AuctionRepository auctionRepository;
    private final AuctionSettlementService auctionSettlementService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Autowired
    @Lazy
    private AuctionLifecycleService self;

    public AuctionLifecycleServiceImpl(
            AuctionRepository auctionRepository,
            AuctionSettlementService auctionSettlementService,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.auctionRepository = auctionRepository;
        this.auctionSettlementService = auctionSettlementService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Thiết lập self-proxy phục vụ điều phối transaction độc lập giữa các phiên hoặc hỗ trợ test.
     */
    public void setSelf(AuctionLifecycleService self) {
        this.self = self;
    }

    /**
     * Mở một phiên đấu giá cụ thể từ SCHEDULED sang ACTIVE.
     *
     * Luồng xử lý:
     * 1. Khóa bi quan bản ghi Auction qua findByIdForUpdate để ngăn tranh chấp đồng thời giữa các worker.
     * 2. Kiểm tra trạng thái hiện tại: chỉ chấp nhận khi phiên đang ở trạng thái SCHEDULED. Nếu không, bỏ qua (idempotent).
     * 3. Kiểm tra mốc thời gian: đảm bảo startTime <= now. Nếu chưa đến giờ mở, bỏ qua.
     * 4. Chuyển trạng thái sang ACTIVE và lưu cập nhật vào cơ sở dữ liệu.
     * 5. Đăng ký phát AuctionStartedEvent sau khi transaction commit thành công.
     *
     * @param auctionId định danh phiên đấu giá
     * @return true nếu phiên được kích hoạt thành công; false nếu bị bỏ qua do không thỏa điều kiện
     */
    @Override
    @Transactional
    public boolean openAuction(UUID auctionId) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }

        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên đấu giá với ID: " + auctionId));

        // Kiểm tra tính hợp lệ của trạng thái: Chỉ mở phiên khi đang ở trạng thái SCHEDULED
        if (auction.getStatus() != AuctionStatus.SCHEDULED) {
            log.info("Phiên đấu giá [{}] không ở trạng thái SCHEDULED (hiện tại: {}), bỏ qua chuyển trạng thái mở phiên",
                    auctionId, auction.getStatus());
            return false;
        }

        Instant now = clock.instant();

        // Kiểm tra điều kiện thời gian bắt đầu
        if (auction.getStartTime() == null || now.isBefore(auction.getStartTime())) {
            log.info("Phiên đấu giá [{}] chưa đến thời điểm mở (startTime: {}, hiện tại: {}), bỏ qua",
                    auctionId, auction.getStartTime(), now);
            return false;
        }

        // Chuyển đổi trạng thái sang ACTIVE
        auction.setStatus(AuctionStatus.ACTIVE);
        auctionRepository.save(auction);
        log.info("Đã mở phiên đấu giá [{}] thành công sang trạng thái ACTIVE", auctionId);

        // Phát sự kiện miền AuctionStartedEvent sau khi commit thành công
        AuctionStartedEvent event = new AuctionStartedEvent(
                auction.getId(),
                auction.getStartTime(),
                auction.getEndTime(),
                auction.getStartPrice()
        );

        if (eventPublisher != null) {
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info("Giao dịch mở phiên [{}] đã commit thành công, phát AuctionStartedEvent", auctionId);
                        eventPublisher.publishEvent(event);
                    }
                });
            } else {
                eventPublisher.publishEvent(event);
            }
        }

        return true;
    }

    /**
     * Quét và tự động mở tất cả các phiên đấu giá đến giờ bắt đầu.
     *
     * Luồng:
     * - Lấy danh sách các phiên có status = SCHEDULED và startTime <= now.
     * - Duyệt qua từng phiên và gọi openAuction độc lập qua proxy transaction.
     * - Bắt lỗi độc lập từng phiên để lỗi của 1 phiên không làm ảnh hưởng các phiên còn lại.
     * - Các phiên bị lỗi vẫn giữ nguyên status SCHEDULED và sẽ được scheduler tự động retry ở chu kỳ kế tiếp.
     *
     * @return số lượng phiên mở thành công
     */
    @Override
    public int openScheduledAuctions() {
        Instant now = clock.instant();
        List<Auction> scheduledAuctions = auctionRepository.findByStatusAndStartTimeLessThanEqual(AuctionStatus.SCHEDULED, now);
        int openedCount = 0;

        AuctionLifecycleService service = (self != null) ? self : this;
        for (Auction auction : scheduledAuctions) {
            try {
                boolean opened = service.openAuction(auction.getId());
                if (opened) {
                    openedCount++;
                }
            } catch (Exception e) {
                log.error("Lỗi khi tự động mở phiên đấu giá [{}]: {}", auction.getId(), e.getMessage(), e);
            }
        }

        if (openedCount > 0) {
            log.info("Đã tự động mở thành công [{}] phiên đấu giá đến giờ bắt đầu", openedCount);
        }
        return openedCount;
    }

    /**
     * Đóng phiên đấu giá cụ thể và chuyển tiếp sang luồng quyết toán (Settlement Handoff).
     *
     * @param auctionId định danh phiên đấu giá
     */
    @Override
    public void closeAuction(UUID auctionId) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }
        auctionSettlementService.settleAuction(auctionId);
    }

    /**
     * Quét và tự động đóng tất cả các phiên đấu giá đã quá hạn endTime, đồng thời bàn giao quyết toán.
     *
     * @return số lượng phiên đã đóng và quyết toán thành công
     */
    @Override
    public int closeExpiredAuctions() {
        return auctionSettlementService.settleExpiredAuctions();
    }
}

