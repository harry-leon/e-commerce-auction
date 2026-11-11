package com.nexus.auction.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phản hồi chi tiết kết quả giữ kho hoặc chuyển tiếp từ Fulfillment Service (SRS §3.6.1).
 *
 * Chức năng:
 * - Tiếp nhận dữ liệu phiếu giữ kho đã được chuyển đổi sang ORDER từ Fulfillment Service.
 * - Trích xuất danh sách reservationId để gắn vào Order của Commerce Service.
 */
public record ReservationResponseDto(
        String refType,
        UUID refId,
        String status,
        List<ReservationItemDto> items,
        Instant expiresAt
) {
    /**
     * DTO chi tiết từng dòng SKU trong phiếu giữ kho.
     */
    public record ReservationItemDto(
            UUID reservationId,
            UUID skuId,
            UUID warehouseId,
            int quantity,
            String status,
            Instant expiresAt
    ) {
    }
}
