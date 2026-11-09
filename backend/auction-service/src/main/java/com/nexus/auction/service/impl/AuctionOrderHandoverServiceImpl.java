package com.nexus.auction.service.impl;

import com.nexus.auction.client.CommerceOrderClient;
import com.nexus.auction.client.dto.AuctionOrderResult;
import com.nexus.auction.client.dto.CreateAuctionOrderPayload;
import com.nexus.auction.dto.mapper.AuctionSettlementMapper;
import com.nexus.auction.dto.response.AuctionSettlementResponse;
import com.nexus.auction.entity.Auction;
import com.nexus.auction.entity.AuctionSettlement;
import com.nexus.auction.enums.SettlementResult;
import com.nexus.auction.event.AuctionSettledEvent;
import com.nexus.auction.event.AuctionWonEvent;
import com.nexus.auction.repository.AuctionRepository;
import com.nexus.auction.repository.AuctionSettlementRepository;
import com.nexus.auction.service.AuctionOrderHandoverService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt nghiệp vụ tích hợp quyết toán và bàn giao khởi tạo đơn hàng sang Commerce Service (DoD KH-S2-2 / SRS §3.5.3).
 *
 * Chức năng:
 * 1. Tiếp nhận kết quả chốt phiên (Consume Settlement): Đọc kết quả từ auction_settlements và auction, kiểm tra tính toàn vẹn.
 * 2. Khởi tạo Đơn hàng đấu giá (Order Creation Handover): Gọi Commerce Service qua CommerceOrderClient tạo đơn hàng sourceType = AUCTION.
 * 3. Chống trùng lặp & Retry (Idempotency & Resilience): Sử dụng auction_id làm Idempotency-Key và áp dụng retry an toàn.
 * 4. Phát sinh sự kiện xác nhận: Phát sinh domain event AuctionSettledEvent sau khi đơn hàng được lưu thành công.
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5.3: Auction Service không sở hữu bảng orders, phải bàn giao kết quả sang Commerce Service.
 * - Idempotency tuyệt đối: nếu phiên đã có orderId trong DB thì lập tức trả về kết quả hiện tại, không gọi lặp sang Commerce.
 * - Đảm bảo tính toàn vẹn: winnerId không được trùng sellerId, finalPrice phải > 0, skuId không được null.
 * - Sự kiện miền chỉ phát sinh sau khi database transaction commit thành công.
 */
@Service
public class AuctionOrderHandoverServiceImpl implements AuctionOrderHandoverService {

    private static final Logger log = LoggerFactory.getLogger(AuctionOrderHandoverServiceImpl.class);

    private final AuctionRepository auctionRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final CommerceOrderClient commerceOrderClient;
    private final ApplicationEventPublisher eventPublisher;

    public AuctionOrderHandoverServiceImpl(
            AuctionRepository auctionRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            CommerceOrderClient commerceOrderClient,
            ApplicationEventPublisher eventPublisher) {
        this.auctionRepository = auctionRepository;
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.commerceOrderClient = commerceOrderClient;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Bàn giao kết quả phiên đấu giá thành công sang Commerce Service để tạo đơn hàng chính thức.
     *
     * Luồng:
     * 1. Kiểm tra tham số và tra cứu bản ghi settlement của phiên đấu giá.
     * 2. Kiểm tra tính lũy nghiệm (Idempotency): Nếu đã có orderId, bỏ qua việc gọi Commerce và trả về kết quả.
     * 3. Kiểm tra tính toàn vẹn (Integrity Validation): Đảm bảo phiên thắng cuộc (SUCCESS), có người thắng, giá > 0.
     * 4. Gọi Commerce Service qua CommerceOrderClient kèm Idempotency-Key = auctionId và cơ chế retry tự động.
     * 5. Cập nhật orderId nhận được từ Commerce Service vào bản ghi auction_settlements.
     * 6. Phát sinh sự kiện xác nhận AuctionSettledEvent (và AuctionWonEvent) sau khi commit thành công.
     */
    @Override
    @Transactional
    public AuctionSettlementResponse handoverAuctionOrder(UUID auctionId) {
        if (auctionId == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mã phiên đấu giá không được để trống");
        }

        // Bước 1: Tra cứu bản ghi quyết toán phiên đấu giá
        AuctionSettlement settlement = auctionSettlementRepository.findByAuctionId(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException("Chưa tìm thấy kết quả quyết toán cho phiên đấu giá: " + auctionId));

        // Bước 2: Kiểm tra tính lũy nghiệm (Idempotency check)
        if (settlement.getOrderId() != null) {
            log.info("Phiên đấu giá [{}] đã được liên kết với đơn hàng [{}] trước đó (Idempotent), bỏ qua bàn giao",
                    auctionId, settlement.getOrderId());
            return AuctionSettlementMapper.toResponse(settlement);
        }

        // Bước 3: Xác thực tính toàn vẹn của kết quả quyết toán (Acceptance Criterion 1)
        if (settlement.getResult() != SettlementResult.SUCCESS) {
            log.warn("Phiên đấu giá [{}] không ở trạng thái SUCCESS (hiện tại: {}), từ chối bàn giao tạo đơn",
                    auctionId, settlement.getResult());
            throw new BusinessException(ErrorCode.AUCTION_NOT_ACTIVE, "Phiên đấu giá không có người thắng cuộc hợp lệ để tạo đơn hàng");
        }

        if (settlement.getWinnerId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Dữ liệu quyết toán thiếu thông tin người thắng cuộc");
        }

        if (settlement.getFinalPrice() == null || settlement.getFinalPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Mức giá chốt phiên đấu giá phải lớn hơn 0");
        }

        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông tin phiên đấu giá: " + auctionId));

        if (auction.getSellerId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Phiên đấu giá thiếu thông tin người bán");
        }

        if (auction.getSkuId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM_VALUE, "Phiên đấu giá thiếu thông tin biến thể SKU");
        }

        if (settlement.getWinnerId().equals(auction.getSellerId())) {
            throw new BusinessException(ErrorCode.AUCTION_SELLER_CANNOT_BID, "Người thắng cuộc không được trùng với người bán");
        }

        Instant paymentDeadline = settlement.getPaymentDeadline() != null
                ? settlement.getPaymentDeadline()
                : settlement.getSettledAt().plus(24, ChronoUnit.HOURS);

        // Phát sự kiện AuctionWonEvent để thông báo kết quả thắng cuộc hợp lệ
        if (eventPublisher != null) {
            eventPublisher.publishEvent(AuctionWonEvent.of(
                    auctionId,
                    auction.getProductId(),
                    auction.getSkuId(),
                    settlement.getWinnerId(),
                    auction.getSellerId(),
                    settlement.getFinalPrice(),
                    paymentDeadline
            ));
        }

        // Bước 4: Chuẩn bị payload và gọi Commerce Service tạo đơn hàng (Acceptance Criterion 2 & 3)
        CreateAuctionOrderPayload payload = new CreateAuctionOrderPayload(
                auctionId,
                settlement.getWinnerId(),
                auction.getSellerId(),
                auction.getProductId(),
                auction.getSkuId(),
                settlement.getFinalPrice(),
                paymentDeadline,
                null,
                null
        );

        log.info("Bắt đầu bàn giao khởi tạo đơn hàng sang Commerce Service cho phiên [{}], winner [{}], giá [{}]",
                auctionId, settlement.getWinnerId(), settlement.getFinalPrice());

        AuctionOrderResult orderResult = commerceOrderClient.createAuctionOrder(payload);

        if (orderResult == null || orderResult.orderId() == null) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "Commerce Service không trả về mã đơn hàng hợp lệ");
        }

        // Bước 5: Cập nhật orderId vào bản ghi settlement và lưu DB
        settlement.setOrderId(orderResult.orderId());
        if (settlement.getPaymentDeadline() == null) {
            settlement.setPaymentDeadline(paymentDeadline);
        }
        AuctionSettlement savedSettlement = auctionSettlementRepository.save(settlement);

        log.info("Hoàn tất liên kết đơn hàng [{}] cho phiên đấu giá [{}]",
                orderResult.orderId(), auctionId);

        // Bước 6: Phát sinh sự kiện xác nhận AuctionSettledEvent sau khi commit DB thành công (Acceptance Criterion 4)
        if (eventPublisher != null) {
            AuctionSettledEvent settledEvent = AuctionSettledEvent.of(
                    auctionId,
                    orderResult.orderId(),
                    savedSettlement.getWinnerId(),
                    paymentDeadline
            );

            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info("Giao dịch bàn giao phiên [{}] đã commit thành công, phát AuctionSettledEvent với orderId [{}]",
                                auctionId, orderResult.orderId());
                        eventPublisher.publishEvent(settledEvent);
                    }
                });
            } else {
                eventPublisher.publishEvent(settledEvent);
            }
        }

        return AuctionSettlementMapper.toResponse(savedSettlement);
    }
}

