package com.nexus.fulfillment.event;

import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
import com.nexus.fulfillment.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Lắng nghe và xử lý các sự kiện vòng đời đơn hàng, thanh toán và đấu giá để đồng bộ kho (SRS §2.8 & §3.6.1).
 *
 * Chức năng:
 * - OrderPaidEvent: Xác nhận trừ kho thực tế (Commit), chuyển thành OUTBOUND và phát sinh InventoryCommittedEvent.
 * - OrderPaymentFailedEvent: Giải phóng tồn kho giữ tạm, hoàn trả về available_qty và phát sinh InventoryReleasedEvent.
 * - OrderCancelledEvent: Giải phóng tồn kho giữ tạm khi đơn bị hủy, hoàn trả về available_qty và phát sinh InventoryReleasedEvent.
 * - AuctionCreatedEvent: Khóa giữ kho cho phiên đấu giá (AUCTION) với TTL bao quát toàn bộ phiên.
 * - AuctionExtendedEvent: Kéo dài TTL giữ kho khi Anti-sniping kích hoạt (+5 phút hoặc mốc mới).
 * - AuctionCancelledEvent: Tức thời giải phóng tồn kho khi phiên bị hủy.
 * - AuctionFailedEvent: Tức thời giải phóng tồn kho khi phiên thất bại do không có người đặt giá.
 * - AuctionSettledEvent: Chuyển tiếp trạng thái giữ hàng sang Đơn hàng của người thắng cuộc mà không bị Reserve kép.
 *
 * Quy tắc Idempotency:
 * - Các phương thức xử lý kiểm tra trạng thái phiếu giữ hàng; nếu nhận trùng sự kiện hoặc retry nhiều lần
 *   thì tồn kho vẫn chỉ bị trừ hoặc hoàn lại đúng 1 lần duy nhất, không tăng kho thừa.
 */
@Component
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryService inventoryService;

    public InventoryEventListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Lắng nghe sự kiện OrderPaid: thực hiện trừ vĩnh viễn reserved_qty và total_qty.
     * Ghi bản ghi xuất kho thực tế vào sổ cái (OUTBOUND) và phát sinh InventoryCommittedEvent.
     */
    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        log.info("Nhận sự kiện OrderPaid cho orderId: {}, reservationId: {}", event.orderId(), event.reservationId());
        try {
            if (event.reservationId() != null) {
                inventoryService.confirmReservation(event.reservationId());
            } else if (event.orderId() != null) {
                inventoryService.commitByOrder(event.orderId());
            } else {
                log.warn("Sự kiện OrderPaid không chứa cả orderId lẫn reservationId, bỏ qua");
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderPaid cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện OrderPaymentFailed: giảm reserved_qty và hoàn trả về available_qty.
     * Ghi sổ cái RELEASE và phát sinh InventoryReleasedEvent.
     */
    @EventListener
    public void onOrderPaymentFailed(OrderPaymentFailedEvent event) {
        log.info("Nhận sự kiện OrderPaymentFailed cho orderId: {}, lý do: {}", event.orderId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "PAYMENT_FAILED";
            if (event.reservationId() != null) {
                inventoryService.releaseReservation(event.reservationId(), reason);
            } else if (event.orderId() != null) {
                inventoryService.releaseByOrder(event.orderId(), reason);
            } else {
                log.warn("Sự kiện OrderPaymentFailed không chứa cả orderId lẫn reservationId, bỏ qua");
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderPaymentFailed cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện OrderCancelled: giảm reserved_qty và hoàn trả về available_qty.
     * Ghi sổ cái RELEASE và phát sinh InventoryReleasedEvent.
     */
    @EventListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        log.info("Nhận sự kiện OrderCancelled cho orderId: {}, lý do: {}", event.orderId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "ORDER_CANCELLED";
            if (event.reservationId() != null) {
                inventoryService.releaseReservation(event.reservationId(), reason);
            } else if (event.orderId() != null) {
                inventoryService.releaseByOrder(event.orderId(), reason);
            } else {
                log.warn("Sự kiện OrderCancelled không chứa cả orderId lẫn reservationId, bỏ qua");
            }
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện OrderCancelled cho orderId {}: {}", event.orderId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện AuctionCreated: thực hiện kiểm tra và giữ kho cho phiên đấu giá (SRS §3.6.1).
     * Thiết lập TTL bao quát toàn bộ thời gian diễn ra phiên đấu giá (từ startTime đến endTime).
     */
    @EventListener
    public void onAuctionCreated(AuctionCreatedEvent event) {
        log.info("Nhận sự kiện AuctionCreated cho auctionId: {}, skuId: {}", event.auctionId(), event.skuId());
        try {
            ReserveAuctionInventoryRequest request = new ReserveAuctionInventoryRequest(
                    event.skuId(),
                    event.warehouseId(),
                    event.startTime(),
                    event.endTime(),
                    event.quantity()
            );
            inventoryService.reserveAuctionStock(event.auctionId(), request);
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện AuctionCreated cho auctionId {}: {}", event.auctionId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện AuctionExtended: kéo dài thời hạn giữ kho khi có Anti-sniping (SRS §2.6 & §3.6.1).
     */
    @EventListener
    public void onAuctionExtended(AuctionExtendedEvent event) {
        log.info("Nhận sự kiện AuctionExtended cho auctionId: {}, extensionMinutes: {}",
                event.auctionId(), event.extensionMinutes());
        try {
            ExtendAuctionReservationRequest request = new ExtendAuctionReservationRequest(
                    event.extensionMinutes(),
                    event.newEndTime()
            );
            inventoryService.extendAuctionReservation(event.auctionId(), request);
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện AuctionExtended cho auctionId {}: {}", event.auctionId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện AuctionCancelled: giải phóng tồn kho khi phiên bị hủy (SRS §3.6.1).
     */
    @EventListener
    public void onAuctionCancelled(AuctionCancelledEvent event) {
        log.info("Nhận sự kiện AuctionCancelled cho auctionId: {}, lý do: {}", event.auctionId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "AUCTION_CANCELLED";
            inventoryService.releaseByAuction(event.auctionId(), reason);
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện AuctionCancelled cho auctionId {}: {}", event.auctionId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện AuctionFailed: giải phóng tồn kho khi phiên kết thúc không có ai đặt giá (SRS §3.6.1).
     */
    @EventListener
    public void onAuctionFailed(AuctionFailedEvent event) {
        log.info("Nhận sự kiện AuctionFailed cho auctionId: {}, lý do: {}", event.auctionId(), event.reason());
        try {
            String reason = (event.reason() != null && !event.reason().isBlank()) ? event.reason() : "AUCTION_FAILED";
            inventoryService.releaseByAuction(event.auctionId(), reason);
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện AuctionFailed cho auctionId {}: {}", event.auctionId(), ex.getMessage(), ex);
            throw ex;
        }
    }

    /**
     * Lắng nghe sự kiện AuctionSettled: chuyển tiếp trạng thái giữ hàng sang Đơn hàng của Winner (SRS §3.6.1).
     * Đảm bảo không xảy ra hiện tượng Reserve kép (Double Reservation).
     */
    @EventListener
    public void onAuctionSettled(AuctionSettledEvent event) {
        log.info("Nhận sự kiện AuctionSettled cho auctionId: {}, orderId: {}, winnerId: {}",
                event.auctionId(), event.orderId(), event.winnerId());
        try {
            TransitionAuctionToOrderRequest request = new TransitionAuctionToOrderRequest(
                    event.orderId(),
                    null,
                    event.paymentDeadline()
            );
            inventoryService.transitionAuctionToOrder(event.auctionId(), request);
        } catch (Exception ex) {
            log.error("Lỗi khi xử lý sự kiện AuctionSettled cho auctionId {}: {}", event.auctionId(), ex.getMessage(), ex);
            throw ex;
        }
    }
}
