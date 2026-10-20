package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.ReservationRefType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu tạo phiếu giữ hàng tồn kho (Create Reservation Request).
 *
 * Chức năng: Tiếp nhận thông tin order_id, danh sách mặt hàng SKU, số lượng yêu cầu và thời gian hết hạn (expires_at)
 * phục vụ việc giữ hàng tạm thời khi người dùng checkout đơn hàng hoặc thắng đấu giá (SRS §3.6.1).
 *
 * @param orderId   Mã đơn hàng liên kết (hoặc ref_id)
 * @param refType   Loại tham chiếu (ORDER hoặc AUCTION; mặc định là ORDER)
 * @param expiresAt Thời gian hết hạn phiếu giữ (nếu null, hệ thống tự động gán theo cấu hình INVENTORY_RESERVATION_TIMEOUT_MINUTES = 30 phút)
 * @param items     Danh sách các mặt hàng SKU và số lượng cần giữ (bắt buộc)
 */
@Schema(description = "Yêu cầu tạo phiếu giữ hàng tồn kho")
public record CreateReservationRequest(
        @NotNull(message = "order_id is required")
        @Schema(description = "Mã đơn hàng liên kết", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID orderId,

        @Schema(description = "Loại tham chiếu nguồn (mặc định ORDER)", example = "ORDER")
        ReservationRefType refType,

        @Schema(description = "Thời gian hết hạn hiệu lực của phiếu giữ (UTC timestamp)", example = "2026-10-20T10:00:00Z")
        Instant expiresAt,

        @NotEmpty(message = "items list must not be empty")
        @Valid
        @Schema(description = "Danh sách mặt hàng và số lượng cần giữ", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ReservationItemRequest> items
) {

    public CreateReservationRequest {
        if (refType == null) {
            refType = ReservationRefType.ORDER;
        }
    }
}
