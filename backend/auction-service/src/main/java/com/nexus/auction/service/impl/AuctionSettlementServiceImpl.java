package com.nexus.auction.service.impl;

import com.nexus.auction.dto.mapper.AuctionSettlementMapper;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.entity.Bid;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.repository.BidRepository;
import com.nexus.auction.service.AuctionSettlementService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai nghiệp vụ quyết toán phiên đấu giá và xác định người chiến thắng (DoD HG-S2-1).
 *
 * Chức năng:
 * - Khóa phiên đấu giá khi chạm mốc thời gian kết thúc (now >= end_time).
 * - Xác định lượt bid hợp lệ cao nhất và người chiến thắng (Winner Determination).
 * - Ghi nhận settlement với kết quả SUCCESS (kèm winnerId, finalPrice, deadline +24h) hoặc FAILED (nếu không có bid).
 * - Ngăn chặn triệt để lượt đặt giá mới sau khi phiên bị khóa hoặc đã kết thúc.
 * - Đảm bảo tính lũy kế (idempotent), chỉ quyết toán 1 lần duy nhất cho mỗi phiên đấu giá.
 *
 * Quy tắc và lý do:
 * - Dùng Clock để dễ dàng kiểm thử các mốc thời gian.
 * - Khóa phiên bi quan (findByIdForUpdate) trong transaction để tránh race condition giữa các worker chốt phiên.
 * - Bắt ngoại lệ DataIntegrityViolationException trên ràng buộc unique uq_settlement_auction để đảm bảo an toàn tuyệt đối.
 * - Tự tham chiếu self-proxy để mỗi phiên trong scheduler chạy transaction độc lập, tránh lỗi dây chuyền rollback cả batch.
 */
@Service
public class AuctionSettlementServiceImpl implements AuctionSettlementService {

    private static final Logger log = LoggerFactory.getLogger(AuctionSettlementServiceImpl.class);

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final Clock clock;

    @Autowired
    @Lazy
    private AuctionSettlementService self;

    public AuctionSettlementServiceImpl(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            Clock clock) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.clock = clock;
    }

    /**
     * Quyết toán phiên đấu giá với bảo đảm tính lũy kế (Idempotency).
     *
     * Luồng:
     * 1. Tra cứu bản ghi settlement trong bảng auction_settlements. Nếu đã có, trả về ngay kết quả đã lưu.
     * 2. Khóa phiên trong bảng auctions bằng khóa bi quan để đồng bộ hóa.
     * 3. Kiểm tra tính hợp lệ về thời gian: không cho phép quyết toán phiên khi now < end_time và trạng thái còn ACTIVE.
     * 4. Chuyển trạng thái phiên sang ENDED để ngăn chặn mọi lượt bid mới.
     * 5. Xác định lượt bid cao nhất:
     *    - Nếu có bid: Tạo settlement SUCCESS, winnerId = bidderId, finalPrice = amount, deadline = now + 24h.
     *      Cập nhật trạng thái auction thành SETTLED.
     *    - Nếu không có bid: Tạo settlement FAILED, winnerId = null, finalPrice = null, deadline = null.
     *      Giữ trạng thái auction là ENDED.
     * 6. Lưu auction và settlement vào DB.
     */
    @Override
    @Transactional
    public AuctionSettlementResponse settleAuction(UUID auctionId) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }

        // Bước 1: Kiểm tra tính lũy kế (Idempotency check)
        Optional<AuctionSettlement> existingSettlement = auctionSettlementRepository.findByAuctionId(auctionId);
        if (existingSettlement.isPresent()) {
            log.info("Phiên đấu giá [{}] đã được quyết toán trước đó, trả về kết quả hiện tại (Idempotent)", auctionId);
            return AuctionSettlementMapper.toResponse(existingSettlement.get());
        }

        // Bước 2: Khóa phiên bi quan để ngăn tranh chấp giữa các worker đồng thời
        Auction auction = auctionRepository.findByIdForUpdate(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy phiên đấu giá với ID: " + auctionId));

        // Kiểm tra lại sau khi đã lấy lock xem thread khác đã kịp insert settlement hay chưa
        existingSettlement = auctionSettlementRepository.findByAuctionId(auctionId);
        if (existingSettlement.isPresent()) {
            log.info("Phiên đấu giá [{}] vừa được quyết toán bởi tiến trình song song", auctionId);
            return AuctionSettlementMapper.toResponse(existingSettlement.get());
        }

        Instant now = clock.instant();

        // Bước 3: Kiểm tra trạng thái và mốc thời gian kết thúc
        if (auction.getStatus() == AuctionStatus.DRAFT
                || auction.getStatus() == AuctionStatus.SCHEDULED
                || auction.getStatus() == AuctionStatus.CANCELLED
                || auction.getStatus() == AuctionStatus.SETTLED) {
            throw new BusinessException(
                    ErrorCode.AUCTION_NOT_ACTIVE,
                    "Không thể quyết toán phiên đấu giá ở trạng thái " + auction.getStatus());
        }

        if (auction.getStatus() == AuctionStatus.ACTIVE && now.isBefore(auction.getEndTime())) {
            throw new BusinessException(
                    ErrorCode.AUCTION_NOT_ACTIVE,
                    "Phiên đấu giá vẫn đang trong thời gian diễn ra, chưa đến thời điểm kết thúc");
        }

        // Bước 4: Khóa phiên - đưa về trạng thái ENDED để ngăn chặn mọi thao tác đặt giá mới
        auction.setStatus(AuctionStatus.ENDED);

        // Bước 5: Xác định người chiến thắng từ lượt bid hợp lệ cao nhất
        Optional<Bid> highestBid = bidRepository.findFirstByAuctionIdOrderByAmountDescPlacedAtAsc(auctionId);

        AuctionSettlement settlement = new AuctionSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setAuctionId(auctionId);
        settlement.setSettledAt(now);

        if (highestBid.isPresent()) {
            Bid winBid = highestBid.get();
            settlement.setWinnerId(winBid.getBidderId());
            settlement.setFinalPrice(winBid.getAmount());
            settlement.setResult(SettlementResult.SUCCESS);
            settlement.setPaymentDeadline(now.plus(24, ChronoUnit.HOURS));

            // Đồng bộ dữ liệu hiển thị trên entity auction và chuyển sang SETTLED
            auction.setHighestBidderId(winBid.getBidderId());
            auction.setCurrentPrice(winBid.getAmount());
            auction.setStatus(AuctionStatus.SETTLED);

            log.info("Phiên [{}] quyết toán THÀNH CÔNG: Winner [{}], Giá chốt [{}]",
                    auctionId, winBid.getBidderId(), winBid.getAmount());
        } else {
            // Không có lượt bid nào: phiên thất bại
            settlement.setWinnerId(null);
            settlement.setFinalPrice(null);
            settlement.setResult(SettlementResult.FAILED);
            settlement.setPaymentDeadline(null);

            // Giữ trạng thái ENDED để lưu vết phiên đã đóng tự nhiên mà không có người mua
            auction.setStatus(AuctionStatus.ENDED);

            log.info("Phiên [{}] quyết toán THẤT BẠI do không có bất kỳ lượt đặt giá nào", auctionId);
        }

        auctionRepository.save(auction);

        try {
            AuctionSettlement savedSettlement = auctionSettlementRepository.save(settlement);
            return AuctionSettlementMapper.toResponse(savedSettlement);
        } catch (DataIntegrityViolationException e) {
            // Đề phòng trường hợp hiếm gặp khi có 2 giao dịch lọt qua lock, bản ghi thứ 2 sẽ chạm UNIQUE uq_settlement_auction
            log.warn("Phát hiện xung đột unique constraint khi lưu settlement phiên [{}], đọc lại bản ghi gốc", auctionId);
            return auctionSettlementRepository.findByAuctionId(auctionId)
                    .map(AuctionSettlementMapper::toResponse)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Quét các phiên đang ACTIVE và đã quá endTime để tự động quyết toán.
     * Luồng: Lấy danh sách phiên hết hạn -> Gọi settleAuction qua self-proxy cho từng phiên độc lập.
     * Lý do không đặt @Transactional tại đây: Đảm bảo mỗi phiên có transaction độc lập, một phiên lỗi không làm rollback toàn bộ batch.
     */
    @Override
    public int settleExpiredAuctions() {
        Instant now = clock.instant();
        List<Auction> expiredAuctions = auctionRepository.findByStatusAndEndTimeLessThanEqual(AuctionStatus.ACTIVE, now);
        int settledCount = 0;

        AuctionSettlementService service = (self != null) ? self : this;
        for (Auction auction : expiredAuctions) {
            try {
                service.settleAuction(auction.getId());
                settledCount++;
            } catch (Exception e) {
                log.error("Lỗi khi tự động quyết toán phiên đấu giá [{}]: {}", auction.getId(), e.getMessage(), e);
            }
        }

        if (settledCount > 0) {
            log.info("Đã tự động quyết toán [{}] phiên đấu giá hết hạn thành công", settledCount);
        }
        return settledCount;
    }

    /**
     * Tra cứu thông tin quyết toán hiện tại của một phiên đấu giá.
     */
    @Override
    @Transactional(readOnly = true)
    public AuctionSettlementResponse getSettlementByAuctionId(UUID auctionId) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }
        AuctionSettlement settlement = auctionSettlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa có kết quả quyết toán cho phiên đấu giá: " + auctionId));
        return AuctionSettlementMapper.toResponse(settlement);
    }

    /**
     * Kiểm tra phiên có chấp nhận lượt bid mới hay không.
     * Quy tắc: Chỉ chấp nhận khi phiên đang ACTIVE và thời điểm bid trước endTime.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canAcceptBid(UUID auctionId, Instant bidTime) {
        if (auctionId == null || bidTime == null) {
            return false;
        }

        Optional<Auction> auctionOpt = auctionRepository.findById(auctionId);
        if (auctionOpt.isEmpty()) {
            return false;
        }

        Auction auction = auctionOpt.get();

        // Nếu phiên đã có bản ghi settlement, dứt khoát từ chối nhận bid
        if (auctionSettlementRepository.existsByAuctionId(auctionId)) {
            return false;
        }

        // Chỉ cho phép đặt giá khi trạng thái là ACTIVE và chưa chạm mốc endTime
        return auction.getStatus() == AuctionStatus.ACTIVE && bidTime.isBefore(auction.getEndTime());
    }
}

