package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.ReservationRefType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Yêu cầu giải phóng phiếu giữ hàng theo mã tham chiếu đơn hàng (SRS §3.6.1).
 *
 * Chức năng:
 * - Được Commerce Service gọi khi đơn hàng bị người dùng hủy hoặc thanh toán không thành công.
 *
 * @param refType Loại tham chiếu (ORDER hoặc AUCTION)
 * @param refId   Mã đơn hàng hoặc phiên đấu giá cần giải phóng tồn kho
 * @param reason  Lý do giải phóng (ví dụ: BUYER_CANCELLED, PAYMENT_FAILED)
 */
public record ReleaseReservationRequest(
        @NotNull(message = "refType không được để trống")
        ReservationRefType refType,

        @NotNull(message = "refId không được để trống")
        UUID refId,

        String reason
) {
}
