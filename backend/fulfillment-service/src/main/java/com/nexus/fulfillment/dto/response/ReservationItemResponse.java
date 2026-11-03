package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Chi tiết phiếu giữ hàng của từng SKU trong kết quả đặt giữ kho (SRS §3.6.1).
 *
 * @param reservationId Mã định danh phiếu giữ hàng
 * @param skuId         Mã biến thể SKU
 * @param warehouseId   Mã kho giữ hàng
 * @param quantity      Số lượng đã giữ
 * @param status        Trạng thái phiếu (PENDING, CONFIRMED, RELEASED, EXPIRED)
 * @param expiresAt     Thời điểm hết hạn giữ kho
 */
public record ReservationItemResponse(
        UUID reservationId,
        UUID skuId,
        UUID warehouseId,
        int quantity,
        ReservationStatus status,
        Instant expiresAt
) {
}
