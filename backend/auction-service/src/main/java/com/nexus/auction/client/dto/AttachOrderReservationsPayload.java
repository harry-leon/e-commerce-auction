package com.nexus.auction.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Payload gửi sang Commerce Service để gắn các reservationId của phiếu giữ kho vào đơn hàng.
 *
 * Chức năng:
 * - Sau khi chuyển tiếp kho thành công tại Fulfillment, Auction Service chuyển giao các reservationId sang Commerce.
 * - Cho phép PaymentService kiểm tra trước khi cấp URL thanh toán VNPAY.
 */
public record AttachOrderReservationsPayload(
        List<UUID> reservationIds
) {
}
