package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Yêu cầu xác nhận xuất kho (Commit Reservation Request).
 *
 * Chức năng: Tiếp nhận thông tin bổ sung khi xác nhận xuất kho chính thức sau khi thanh toán thành công (SRS §2.8 & §3.6.1).
 *
 * @param reason Lý do hoặc ghi chú xuất kho (tùy chọn, ví dụ: "PAYMENT_SUCCESS", "AUCTION_SETTLED")
 */
@Schema(description = "Yêu cầu xác nhận xuất kho (Commit Reservation)")
public record CommitReservationRequest(
        @Schema(description = "Lý do hoặc ghi chú xác nhận xuất kho", example = "PAYMENT_SUCCESS")
        String reason
) {
}
