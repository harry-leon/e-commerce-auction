package com.nexus.fulfillment.client;

import java.util.UUID;

/**
 * Client kết nối với Notification Service để kích hoạt gửi email giao dịch (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Gửi yêu cầu email hóa đơn điện tử khi thanh toán thành công (OrderPaid).
 * - Gửi email cảnh báo giải phóng kho khi thanh toán thất bại (OrderPaymentFailed).
 *
 * Nguyên tắc cô lập lỗi (§5.2 SRS):
 * - Bất kỳ sự cố nào từ Notification Service (timeout, lỗi mạng, SMTP sập) không bao giờ
 *   được làm gián đoạn hoặc rollback tiến trình cập nhật tồn kho (Commit/Release).
 */
public interface NotificationClient {

    /**
     * Kích hoạt gửi email xác nhận thanh toán thành công và hóa đơn điện tử.
     *
     * @param orderId Mã định danh đơn hàng
     * @param orderNo Mã hiển thị đơn hàng
     */
    void sendOrderPaidNotification(UUID orderId, String orderNo);

    /**
     * Kích hoạt gửi email cảnh báo thanh toán thất bại và nhắc nhở thời hạn giữ kho.
     *
     * @param orderId Mã định danh đơn hàng
     * @param orderNo Mã hiển thị đơn hàng
     * @param reason  Lý do thanh toán thất bại
     */
    void sendPaymentFailedNotification(UUID orderId, String orderNo, String reason);
}
