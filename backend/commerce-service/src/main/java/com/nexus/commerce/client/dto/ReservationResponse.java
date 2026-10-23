package com.nexus.commerce.client.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO phản hồi kết quả giữ chỗ tồn kho từ Fulfillment / Inventory Service.
 *
 * @param reservationId Mã định danh phiếu giữ chỗ tồn kho
 * @param orderId       Mã đơn hàng liên kết
 * @param status        Trạng thái giữ chỗ (PENDING, CONFIRMED, RELEASED)
 * @param expiresAt     Thời điểm hết hạn giữ chỗ (mặc định sau 15 phút)
 */
public record ReservationResponse(
        UUID reservationId,
        UUID orderId,
        String status,
        Instant expiresAt
) {
}
