package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Yêu cầu hủy giữ hàng và hoàn kho (Release Reservation Request).
 *
 * Chức năng: Tiếp nhận thông tin bổ sung khi giải phóng tồn kho bị giữ (SRS §2.8 & §3.6.1)
 * khi đơn hàng bị hủy, thanh toán thất bại hoặc quá hạn.
 *
 * @param reason Lý do hủy giữ hàng (tùy chọn, ví dụ: "ORDER_CANCELLED", "PAYMENT_EXPIRED", "USER_ABANDONED")
 */
@Schema(description = "Yêu cầu giải phóng tồn kho đã giữ (Release Reservation)")
public record ReleaseReservationRequest(
        @Schema(description = "Lý do hoàn trả tồn kho", example = "ORDER_CANCELLED")
        String reason
) {
}
