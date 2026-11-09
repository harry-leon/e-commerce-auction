package com.nexus.auction.service.impl;

import com.nexus.auction.client.CommerceOrderClient;
import com.nexus.auction.client.FulfillmentInventoryClient;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Cài đặt nghiệp vụ tích hợp quyết toán và bàn giao khởi tạo đơn hàng sang Commerce Service (DoD KH-S2-2 / SRS §3.5.3).
 *
 * Chức năng:
 * 1. Tiếp nhận kết quả chốt phiên (Consume Settlement): Đọc kết quả từ auction_settlements và auction, kiểm tra tính toàn vẹn.
 * 2. Khởi tạo Đơn hàng đấu giá (Order Creation Handover): Gọi Commerce Service qua CommerceOrderClient tạo đơn hàng sourceType = AUCTION.
 * 3. Nối chuyển tiếp giữ kho AUCTION -> ORDER (Inventory Transition): Gọi Fulfillment Service chuyển tiếp phiếu giữ kho sang orderId.
 * 4. Bàn giao đầy đủ dữ liệu cho thanh toán: Gắn reservationIds vào đơn hàng Commerce và xác lập paymentDeadline 24h.
 * 5. Chống trùng lặp & Retry (Idempotency & Resilience): Sử dụng auction_id làm Idempotency-Key và áp dụng retry an toàn.
 * 6. Phát sinh sự kiện xác nhận: Phát sinh domain event AuctionSettledEvent sau khi đơn hàng được lưu thành công.
 *
 * Quy tắc và lý do:
 * - Tuân thủ SRS §3.5.3: Auction Service không sở hữu bảng orders, phải bàn giao kết quả sang Commerce Service.
 * - Idempotency tuyệt đối: nếu phiên đã có orderId trong DB thì lập tức trả về kết quả hiện tại, không gọi lặp sang Commerce.
 * - Mở transaction độc lập (REQUIRES_NEW): đảm bảo khi gọi từ afterCommit() hoặc từ scheduler, transaction luôn được cô lập
 *   và orderId chắc chắn được ghi xuống CSDL độc lập (bảo vệ liên kết settlement.orderId).
 * - Đảm bảo tính toàn vẹn: winnerId không được trùng sellerId, finalPrice phải > 0, skuId không được null.
 */
@Service
public class AuctionOrderHandoverServiceImpl implements AuctionOrderHandoverService {

    private static final Logger log = LoggerFactory.getLogger(AuctionOrderHandoverServiceImpl.class);

    private final AuctionRepository auctionRepository;
    private final AuctionSettlementRepository auctionSettlementRepository;
    private final CommerceOrderClient commerceOrderClient;
    private final FulfillmentInventoryClient fulfillmentInventoryClient;
    private final ApplicationEventPublisher eventPublisher;

    @Autowired
    public AuctionOrderHandoverServiceImpl(
            AuctionRepository auctionRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            CommerceOrderClient commerceOrderClient,
            @Autowired(required = false) FulfillmentInventoryClient fulfillmentInventoryClient,
            ApplicationEventPublisher eventPublisher) {
        this.auctionRepository = auctionRepository;
        this.auctionSettlementRepository = auctionSettlementRepository;
        this.commerceOrderClient = commerceOrderClient;
        this.fulfillmentInventoryClient = fulfillmentInventoryClient;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Constructor thuận tiện cho backward-compatibility với các test cũ chưa mock FulfillmentInventoryClient.
     */
    public AuctionOrderHandoverServiceImpl(
            AuctionRepository auctionRepository,
            AuctionSettlementRepository auctionSettlementRepository,
            CommerceOrderClient commerceOrderClient,
            ApplicationEventPublisher eventPublisher) {
        this(auctionRepository, auctionSettlementRepository, commerceOrderClient, null, eventPublisher);
    }

    /**
     * Bàn giao kết quả phiên đấu giá thành công sang Commerce Service để tạo đơn hàng chính thức.
     *
     * Luồng:
     * 1. Kiểm tra tham số và tra cứu bản ghi settlement của phiên đấu giá.
     * 2. Kiểm tra tính lũy nghiệm (Idempotency): Nếu đã có orderId, bỏ qua việc gọi Commerce và trả về kết quả.
     * 3. Kiểm tra tính toàn vẹn (Integrity Validation): Đảm bảo phiên thắng cuộc (SUCCESS), có người thắng, giá > 0.
     * 4. Gọi Commerce Service qua CommerceOrderClient kèm Idempotency-Key = auctionId và cơ chế retry tự động.
     * 5. Gọi Fulfillment Service qua FulfillmentInventoryClient để chuyển tiếp phiếu kho AUCTION -> ORDER thực tế (SRS §3.6.1).
     * 6. Gọi Commerce Service gắn các reservationId của phiếu kho vào đơn hàng để PaymentService sẵn sàng thanh toán.
     * 7. Cập nhật orderId và paymentDeadline vào bản ghi auction_settlements với saveAndFlush trong transaction độc lập.
     * 8. Phát sinh sự kiện xác nhận AuctionSettledEvent sau khi commit thành công.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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

        // Bước 5: Nối chuyển tiếp giữ kho AUCTION -> ORDER tại Fulfillment Service (DoD KH-S2-1 & Feedback Point 3)
        List<UUID> reservationIds = Collections.emptyList();
        if (fulfillmentInventoryClient != null) {
            try {
                log.info("Gọi Fulfillment Service chuyển tiếp giữ kho AUCTION -> ORDER cho phiên [{}], orderId [{}]",
                        auctionId, orderResult.orderId());
                reservationIds = fulfillmentInventoryClient.transitionAuctionToOrder(
                        auctionId,
                        orderResult.orderId(),
                        paymentDeadline
                );
            } catch (Exception ex) {
                log.error("Lỗi khi chuyển tiếp giữ kho tại Fulfillment cho phiên [{}]: {}", auctionId, ex.getMessage(), ex);
                throw ex;
            }
        }

        // Bước 6: Gắn danh sách reservationIds vào đơn hàng Commerce (Feedback Point 4)
        if (reservationIds != null && !reservationIds.isEmpty()) {
            try {
                log.info("Gắn [{}] reservationId vào đơn hàng [{}] tại Commerce Service",
                        reservationIds.size(), orderResult.orderId());
                commerceOrderClient.attachOrderReservations(orderResult.orderId(), reservationIds);
            } catch (Exception ex) {
                log.warn("Không thể gắn reservationIds vào đơn hàng [{}] ngay lúc này: {}",
                        orderResult.orderId(), ex.getMessage());
            }
        }

        // Bước 7: Cập nhật orderId vào bản ghi settlement và lưu DB ngay lập tức (saveAndFlush)
        settlement.setOrderId(orderResult.orderId());
        if (settlement.getPaymentDeadline() == null) {
            settlement.setPaymentDeadline(paymentDeadline);
        }
        AuctionSettlement savedSettlement = auctionSettlementRepository.saveAndFlush(settlement);

        log.info("Hoàn tất liên kết đơn hàng [{}] cho phiên đấu giá [{}] trong transaction độc lập",
                orderResult.orderId(), auctionId);

        // Bước 8: Phát sinh sự kiện xác nhận AuctionSettledEvent sau khi commit DB thành công (Acceptance Criterion 4)
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

