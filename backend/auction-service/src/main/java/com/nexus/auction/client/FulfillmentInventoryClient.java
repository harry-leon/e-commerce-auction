package com.nexus.auction.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cổng giao tiếp với Fulfillment Service để quản lý vòng đời giữ kho của phiên đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Chuyển tiếp trạng thái giữ hàng từ phiên đấu giá sang Đơn hàng của người thắng cuộc (Transition to Order).
 * - Đảm bảo không xảy ra hiện tượng Reserve kép (Double Reservation) hoặc mất hàng trong tích tắc chuyển đổi.
 * - Lấy về danh sách mã phiếu giữ kho (reservation IDs) đã chuyển sang đơn hàng để gắn vào Commerce Service.
 */
public interface FulfillmentInventoryClient {

    /**
     * Chuyển tiếp phiếu giữ kho từ AUCTION sang ORDER khi chốt phiên đấu giá thành công.
     *
     * @param auctionId       Mã phiên đấu giá cần chuyển tiếp
     * @param orderId         Mã đơn hàng Commerce đã được tạo cho người thắng
     * @param paymentDeadline Thời hạn thanh toán của đơn hàng (24 giờ)
     * @return Danh sách mã phiếu giữ kho (reservationId) thuộc đơn hàng
     */
    List<UUID> transitionAuctionToOrder(UUID auctionId, UUID orderId, Instant paymentDeadline);
}
