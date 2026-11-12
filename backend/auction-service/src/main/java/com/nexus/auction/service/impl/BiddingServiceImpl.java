package com.nexus.auction.service.impl;

import com.nexus.auction.client.ReputationEligibilityClient;
import com.nexus.auction.config.AuctionProperties;
import com.nexus.auction.dto.mapper.BidMapper;
import com.nexus.auction.dto.request.PlaceBidRequest;
import com.nexus.auction.dto.response.BidResponse;
import com.nexus.auction.dto.response.PlaceBidResult;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.Bid;
import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.event.AuctionExtendedEvent;
import com.nexus.auction.event.BidPlacedEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.repository.BidRepository;
import com.nexus.auction.service.BiddingService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt nghiệp vụ Bidding Engine & Concurrency Control (HG-S2-3).
 *
 * Chức năng:
 * - Đặt giá thầu cho phiên đấu giá trong thời gian thực (Atomic & Idempotent).
 * - Kiểm soát xung đột đặt giá đồng thời (Concurrency Control) qua Optimistic Locking (@Version) và cơ chế xử lý lỗi nhất quán.
 * - Tự động phát hiện và gia hạn phiên khi có bid phút chót (Anti-sniping protection) tuân thủ giới hạn maxExtensionCount.
 * - Phát Domain Event (BidPlacedEvent) sau khi cơ sở dữ liệu đã commit thành công.
 * - Phân trang lịch sử đặt giá.
 *
 * Quy tắc nghiệp vụ (BR-AUC-05, BR-AUC-06, BR-AUC-07, BR-AUC-08, BR-AUC-09):
 * - BR-AUC-05: Chỉ nhận bid khi phiên đang ACTIVE và startTime <= now < endTime.
 * - BR-AUC-06: Người bán (sellerId) không được bid phiên của mình. Người đang giữ giá cao nhất (highestBidderId) không được tự raise giá tiếp.
 * - BR-AUC-07: Mức bid tối thiểu = startPrice nếu chưa có bid, hoặc = currentPrice + bidIncrement nếu đã có bid.
 * - BR-AUC-08: IdempotencyKey duy nhất: gửi lặp lại cùng key trả về kết quả thành công ban đầu mà không tạo bản ghi mới.
 * - BR-AUC-09: Gia hạn endTime nếu now >= endTime - antiSnipingSeconds và extensionCount < maxExtensionCount.
 */
@Service
public class BiddingServiceImpl implements BiddingService {

    private static final Logger log = LoggerFactory.getLogger(BiddingServiceImpl.class);

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final AuctionProperties auctionProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ReputationEligibilityClient reputationEligibilityClient;

    public BiddingServiceImpl(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            AuctionProperties auctionProperties,
            ApplicationEventPublisher eventPublisher,
            Clock clock) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.auctionProperties = auctionProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.reputationEligibilityClient = null;
    }

    @Autowired
    public BiddingServiceImpl(
            AuctionRepository auctionRepository,
            BidRepository bidRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            AuctionProperties auctionProperties,
            ApplicationEventPublisher eventPublisher,
            Clock clock,
            @Autowired(required = false) ReputationEligibilityClient reputationEligibilityClient) {
        this.auctionRepository = auctionRepository;
        this.bidRepository = bidRepository;
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.auctionProperties = auctionProperties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.reputationEligibilityClient = reputationEligibilityClient;
    }

    /**
     * Xử lý đặt giá thầu nguyên tử cho phiên đấu giá.
     * Luồng:
     * 1. Kiểm tra IdempotencyKey: nếu key đã tồn tại trong DB, trả về kết quả idempotent hiện có.
     * 2. Tìm kiếm Auction theo auctionId: ném AUCTION_NOT_FOUND nếu không tìm thấy.
     * 3. Kiểm tra trạng thái và thời gian (BR-AUC-05): phiên phải ACTIVE, chưa settlement, startTime <= now < endTime.
     * 4. Kiểm tra quyền hạn và tư cách đấu giá (BR-AUC-06):
     *    - bidderId không được trùng với sellerId (AUCTION_SELLER_CANNOT_BID - HTTP 422).
     *    - bidderId không được trùng với highestBidderId hiện tại (BID_AMOUNT_TOO_LOW / vi phạm tự raise giá).
     * 5. Kiểm tra mức giá đặt (BR-AUC-07):
     *    - Nếu chưa có bid: amount >= startPrice.
     *    - Nếu đã có bid: amount >= currentPrice + bidIncrement.
     * 6. Kiểm tra Anti-sniping (BR-AUC-09):
     *    - Nếu còn trong cửa sổ antiSnipingSeconds trước endTime và extensionCount < maxExtensionCount:
     *      endTime += antiSnipingSeconds, extensionCount++.
     * 7. Cập nhật Auction (currentPrice, highestBidderId, endTime, extensionCount) và lưu bản ghi Bid.
     * 8. Đăng ký phát BidPlacedEvent SAU KHI TRANSACTION COMMIT thành công.
     */
    @Override
    @Transactional
    public PlaceBidResult placeBid(UUID auctionId, UUID bidderId, PlaceBidRequest request) {
        if (auctionId == null || bidderId == null || request == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Tham số đặt giá không được để trống");
        }

        Instant now = clock.instant();
        String idempotencyKey = request.getIdempotencyKey();

        // 1. Kiểm tra Idempotency: nếu đã gửi thành công trước đó với cùng key, trả kết quả tương ứng
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Bid> existingBidOpt = bidRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existingBidOpt.isPresent()) {
                Bid existingBid = existingBidOpt.get();
                // Đảm bảo request trùng khớp auctionId và bidderId
                if (existingBid.getAuctionId().equals(auctionId) && existingBid.getBidderId().equals(bidderId)) {
                    log.info("Lượt đặt giá với idempotencyKey [{}] đã tồn tại, trả về kết quả ban đầu (Idempotent)", idempotencyKey);
                    Auction currentAuction = auctionRepository.findById(auctionId).orElse(null);
                    BigDecimal currentBid = currentAuction != null ? currentAuction.getCurrentPrice() : existingBid.getAmount();
                    Instant end = currentAuction != null ? currentAuction.getEndTime() : now;
                    long totalBids = bidRepository.countByAuctionId(auctionId);
                    return new PlaceBidResult(BidMapper.toResponse(existingBid), currentBid, totalBids, end, false);
                }
            }
        }

        // 2. Tìm kiếm Auction
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Không tìm thấy phiên đấu giá: " + auctionId));

        // 3. Kiểm tra trạng thái và điều kiện mở phiên (BR-AUC-05)
        if (auctionSettlementRepository.existsByAuctionId(auctionId)
                || auction.getStatus() != AuctionStatus.ACTIVE
                || auction.getStartTime() == null
                || auction.getEndTime() == null
                || now.isBefore(auction.getStartTime())
                || !now.isBefore(auction.getEndTime())) {
            throw new BusinessException(ErrorCode.AUCTION_NOT_ACTIVE, "Phiên đấu giá chưa mở, đã kết thúc hoặc không ở trạng thái nhận bid");
        }

        // 4. Kiểm tra tư cách người đặt giá (BR-AUC-06)
        // Seller không được tự đặt giá cho phiên của mình
        if (Objects.equals(auction.getSellerId(), bidderId)) {
            throw new BusinessException(ErrorCode.AUCTION_SELLER_CANNOT_BID, "Người tạo phiên không được tự đặt giá vào phiên của chính mình");
        }

        // Người đang giữ giá cao nhất không được tự nâng giá tiếp khi chưa có ai vượt qua
        if (Objects.equals(auction.getHighestBidderId(), bidderId)) {
            throw new BusinessException(ErrorCode.BID_AMOUNT_TOO_LOW, "Bạn đang là người trả giá cao nhất, không thể tự nâng giá khi chưa bị vượt");
        }

        // Kiểm tra điểm uy tín tối thiểu của người đặt giá thầu trước khi xử lý mức giá (SRS §3.5.3)
        if (reputationEligibilityClient != null && auctionProperties.getRules().isReputationEnforcementEnabled()) {
            reputationEligibilityClient.assertEligible(bidderId, "BID");
        }

        // 5. Kiểm tra giá đặt tối thiểu (BR-AUC-07)
        BigDecimal minRequiredBid;
        if (auction.getHighestBidderId() == null) {
            // Chưa có ai đặt giá: mức giá tối thiểu là startPrice
            minRequiredBid = auction.getStartPrice();
        } else {
            // Đã có người đặt giá: mức giá tối thiểu là currentPrice + bidIncrement
            BigDecimal increment = auction.getBidIncrement() != null ? auction.getBidIncrement() : BigDecimal.ZERO;
            minRequiredBid = (auction.getCurrentPrice() != null ? auction.getCurrentPrice() : auction.getStartPrice()).add(increment);
        }

        if (request.getAmount() == null || request.getAmount().compareTo(minRequiredBid) < 0) {
            throw new BusinessException(
                    ErrorCode.BID_AMOUNT_TOO_LOW,
                    String.format("Mức giá đặt (%s VND) phải lớn hơn hoặc bằng mức giá yêu cầu tối thiểu (%s VND)",
                            request.getAmount() != null ? request.getAmount().toPlainString() : "0",
                            minRequiredBid.toPlainString()));
        }

        // 6. Cơ chế Anti-sniping protection (BR-AUC-09)
        int antiSnipingSeconds = auctionProperties.getBidding().getAntiSnipingSeconds();
        int maxExtensionCount = auctionProperties.getBidding().getMaxExtensionCount();
        boolean extended = false;

        Instant extensionWindowStart = auction.getEndTime().minus(Duration.ofSeconds(antiSnipingSeconds));
        // Nếu lượt bid gửi trong khoảng [endTime - antiSnipingSeconds, endTime)
        if (!now.isBefore(extensionWindowStart) && now.isBefore(auction.getEndTime())) {
            int currentExtensions = auction.getExtensionCount() != null ? auction.getExtensionCount() : 0;
            if (currentExtensions < maxExtensionCount) {
                auction.setEndTime(auction.getEndTime().plus(Duration.ofSeconds(antiSnipingSeconds)));
                auction.setExtensionCount(currentExtensions + 1);
                extended = true;
                log.info("Kích hoạt Anti-sniping cho phiên [{}]: endTime mới [{}], extensionCount [{}]",
                        auctionId, auction.getEndTime(), auction.getExtensionCount());
            } else {
                log.info("Phiên [{}] đã đạt giới hạn gia hạn tối đa ({}), giữ nguyên endTime", auctionId, maxExtensionCount);
            }
        }

        // 7. Cập nhật thông tin giá cao nhất trên Auction
        auction.setCurrentPrice(request.getAmount());
        auction.setHighestBidderId(bidderId);

        // Lưu bản ghi Bid
        Bid bid = new Bid();
        bid.setId(UUID.randomUUID());
        bid.setAuctionId(auctionId);
        bid.setBidderId(bidderId);
        bid.setAmount(request.getAmount());
        bid.setIdempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey.trim() : null);
        bid.setPlacedAt(now);

        try {
            bidRepository.save(bid);
            auctionRepository.save(auction);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Bị tranh chấp phiên bản với giao dịch đặt giá hoặc quyết toán khác
            log.warn("Xung đột phiên bản Optimistic Lock khi đặt giá phiên [{}]: {}", auctionId, ex.getMessage());
            throw new BusinessException(ErrorCode.BID_CONCURRENT_OUTBID, "Đã có người đặt mức giá này hoặc cao hơn ngay trước bạn");
        } catch (DataIntegrityViolationException ex) {
            // Trùng lặp unique constraint idempotency_key hoặc xung đột DB
            if (idempotencyKey != null && ex.getMessage() != null && ex.getMessage().contains("idempotency_key")) {
                Optional<Bid> duplicateBid = bidRepository.findByIdempotencyKey(idempotencyKey.trim());
                if (duplicateBid.isPresent()) {
                    long totalBids = bidRepository.countByAuctionId(auctionId);
                    return new PlaceBidResult(BidMapper.toResponse(duplicateBid.get()), auction.getCurrentPrice(), totalBids, auction.getEndTime(), false);
                }
            }
            throw new BusinessException(ErrorCode.BID_CONCURRENT_OUTBID, "Đã có người nhanh hơn trả mức giá này, vui lòng nhập giá cao hơn");
        }

        long totalBids = bidRepository.countByAuctionId(auctionId);
        BidResponse bidResponse = BidMapper.toResponse(bid);
        PlaceBidResult result = new PlaceBidResult(bidResponse, auction.getCurrentPrice(), totalBids, auction.getEndTime(), extended);

        // 8. Đăng ký Domain Event: CHỈ PHÁT SỰ KIỆN SAU KHI GIAO DỊCH DATABASE COMMIT THÀNH CÔNG
        BidPlacedEvent event = new BidPlacedEvent(
                auctionId,
                bid.getId(),
                bidderId,
                bid.getAmount(),
                auction.getCurrentPrice(),
                totalBids,
                bid.getPlacedAt(),
                auction.getEndTime(),
                extended
        );

        final AuctionExtendedEvent extendedEvent = extended ? new AuctionExtendedEvent(
                auctionId,
                auction.getEndTime(),
                auction.getExtensionCount() != null ? auction.getExtensionCount() : 0,
                "Phiên đấu giá được gia hạn thêm do có lượt trả giá giờ chót!"
        ) : null;

        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("Giao dịch đặt giá phiên [{}] đã commit thành công, phát BidPlacedEvent", auctionId);
                    eventPublisher.publishEvent(event);
                    if (extendedEvent != null) {
                        log.info("Phát sự kiện AuctionExtendedEvent cho phiên [{}]", auctionId);
                        eventPublisher.publishEvent(extendedEvent);
                    }
                }
            });
        } else {
            eventPublisher.publishEvent(event);
            if (extendedEvent != null) {
                eventPublisher.publishEvent(extendedEvent);
            }
        }

        return result;
    }

    /**
     * Tra cứu lịch sử đặt giá của phiên đấu giá có phân trang.
     */
    @Override
    @Transactional(readOnly = true)
    public Page<BidResponse> getBidHistory(UUID auctionId, Pageable pageable) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }
        if (!auctionRepository.existsById(auctionId)) {
            throw new ResourceNotFoundException(ErrorCode.AUCTION_NOT_FOUND, "Không tìm thấy phiên đấu giá: " + auctionId);
        }
        return bidRepository.findByAuctionId(auctionId, pageable).map(BidMapper::toResponse);
    }
}
