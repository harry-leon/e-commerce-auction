package com.nexus.fulfillment.shipping.adapter.resilience;

/**
 * Trạng thái của Circuit Breaker bảo vệ kết nối tới hãng vận chuyển (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - CLOSED: Mạch đóng, mọi yêu cầu được gửi bình thường tới carrier.
 * - OPEN: Mạch ngắt khi tỷ lệ lỗi vượt ngưỡng, lập tức chặn gọi tới carrier để fail-fast, bảo vệ luồng xử lý đơn hàng.
 * - HALF_OPEN: Mạch mở một phần sau thời gian chờ (cooldown) để kiểm tra thăm dò xem carrier đã hồi phục hay chưa.
 */
public enum CarrierCircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
