package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi đơn hàng bị người mua hoặc hệ thống hủy bỏ (SRS §2.8 OrderCancelled).
 *
 * Chức năng:
 * - Kích hoạt luồng giải phóng giữ hàng (Release Inventory), hoàn trả reserved_qty về available_qty.
 *
 * Đầu vào và kết quả:
 * - Mang thông tin orderId, reservationId (tùy chọn), lý do hủy và thời điểm phát sinh.
 */
public record OrderCancelledEvent(
        UUID orderId,
        UUID reservationId,
        String reason,
        Instant occurredAt
) {
    public static OrderCancelledEvent of(UUID orderId, String reason) {
        return new OrderCancelledEvent(orderId, null, reason, Instant.now());
    }

    public static OrderCancelledEvent of(UUID orderId, UUID reservationId, String reason) {
        return new OrderCancelledEvent(orderId, reservationId, reason, Instant.now());
    }
}
