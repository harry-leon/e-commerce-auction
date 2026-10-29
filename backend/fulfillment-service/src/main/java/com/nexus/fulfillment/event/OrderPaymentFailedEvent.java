package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi thanh toán đơn hàng bị lỗi hoặc thất bại (SRS §2.8 OrderPaymentFailed).
 *
 * Chức năng:
 * - Kích hoạt luồng giải phóng giữ hàng (Release Inventory), hoàn trả reserved_qty về available_qty.
 *
 * Đầu vào và kết quả:
 * - Mang thông tin orderId, reservationId (tùy chọn), lý do lỗi và thời điểm phát sinh.
 */
public record OrderPaymentFailedEvent(
        UUID orderId,
        UUID reservationId,
        String reason,
        Instant occurredAt
) {
    public static OrderPaymentFailedEvent of(UUID orderId, String reason) {
        return new OrderPaymentFailedEvent(orderId, null, reason, Instant.now());
    }

    public static OrderPaymentFailedEvent of(UUID orderId, UUID reservationId, String reason) {
        return new OrderPaymentFailedEvent(orderId, reservationId, reason, Instant.now());
    }
}
