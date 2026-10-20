package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phản hồi thông tin phiếu giữ hàng (Inventory Reservation).
 *
 * Chức năng: Cung cấp toàn bộ thông tin phiếu giữ hàng cho Commerce Service hoặc Auction Service
 * trong toàn bộ vòng đời (tạo phiếu, tra cứu, commit khi thanh toán thành công, release khi hủy đơn).
 *
 * @param reservationId Mã định danh phiếu giữ hàng
 * @param orderId       Mã đơn hàng hoặc mã tham chiếu nguồn (ref_id)
 * @param refType       Loại tham chiếu (ORDER hoặc AUCTION)
 * @param status        Trạng thái phiếu giữ hàng (PENDING, CONFIRMED, RELEASED, EXPIRED)
 * @param createdAt     Thời điểm tạo phiếu giữ hàng
 * @param expiresAt     Thời điểm hết hạn hiệu lực của phiếu giữ hàng
 * @param items         Danh sách các mặt hàng SKU và số lượng tương ứng được giữ
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Thông tin phiếu giữ hàng tồn kho")
public record ReservationResponse(
        @Schema(description = "Mã phiếu giữ hàng", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID reservationId,

        @Schema(description = "Mã đơn hàng liên kết", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
        UUID orderId,

        @Schema(description = "Loại tham chiếu", example = "ORDER")
        ReservationRefType refType,

        @Schema(description = "Trạng thái phiếu giữ hàng", example = "PENDING")
        ReservationStatus status,

        @Schema(description = "Thời điểm tạo phiếu giữ hàng", example = "2026-10-20T09:30:00Z")
        Instant createdAt,

        @Schema(description = "Thời điểm hết hạn hiệu lực giữ hàng", example = "2026-10-20T10:00:00Z")
        Instant expiresAt,

        @Schema(description = "Danh sách mặt hàng được giữ trong phiếu")
        List<ReservationItemResponse> items
) {
}
