package com.nexus.fulfillment.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Sự kiện miền phát sinh khi đơn hàng thanh toán thành công (SRS §2.8 OrderPaid).
 *
 * Chức năng:
 * - Kích hoạt luồng xác nhận trừ kho thực tế (Commit Inventory) trong Fulfillment Service.
 * - Hỗ trợ cả hai mức định danh: orderId (cho toàn bộ đơn) và reservationId (cho từng phiếu giữ).
 *
 * Đầu vào và kết quả:
 * - Mang thông tin orderId, reservationId (tùy chọn), paymentId (tùy chọn) và thời điểm phát sinh.
 */
public record OrderPaidEvent(
        UUID orderId,
        UUID reservationId,
        UUID paymentId,
        Instant occurredAt
) {
    public static OrderPaidEvent of(UUID orderId) {
        return new OrderPaidEvent(orderId, null, null, Instant.now());
    }

    public static OrderPaidEvent of(UUID orderId, UUID reservationId) {
        return new OrderPaidEvent(orderId, reservationId, null, Instant.now());
    }

    public static OrderPaidEvent of(UUID orderId, UUID reservationId, UUID paymentId) {
        return new OrderPaidEvent(orderId, reservationId, paymentId, Instant.now());
    }
}
