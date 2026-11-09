package com.nexus.auction.client;

import com.nexus.auction.client.dto.AuctionOrderResult;
import com.nexus.auction.client.dto.CreateAuctionOrderPayload;
import java.util.List;
import java.util.UUID;

/**
 * Client port giao tiếp service-to-service với Commerce Service để khởi tạo đơn hàng đấu giá (SRS §3.5.3).
 *
 * Chức năng:
 * - Kích hoạt Commerce Service tạo đơn hàng cho người thắng cuộc từ dữ liệu quyết toán.
 * - Đảm bảo tính lũy nghiệm (Idempotency) bằng cách truyền auction_id làm Idempotency-Key.
 * - Gắn các reservationId vào đơn hàng sau khi chuyển tiếp kho tại Fulfillment Service.
 * - Tích hợp cơ chế thử lại (Retry) an toàn khi gặp sự cố mạng tạm thời.
 */
public interface CommerceOrderClient {

    /**
     * Gửi yêu cầu khởi tạo đơn hàng đấu giá sang Commerce Service.
     *
     * @param payload dữ liệu quyết toán phiên đấu giá
     * @return kết quả thông tin đơn hàng đã tạo
     */
    AuctionOrderResult createAuctionOrder(CreateAuctionOrderPayload payload);

    /**
     * Gửi yêu cầu gắn danh sách reservationId vào đơn hàng đã tạo tại Commerce Service.
     *
     * @param orderId        Mã đơn hàng Commerce
     * @param reservationIds Danh sách mã phiếu giữ kho từ Fulfillment Service
     */
    void attachOrderReservations(UUID orderId, List<UUID> reservationIds);
}

