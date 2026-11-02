package com.nexus.notification.enums;

/**
 * Loại sự kiện email giao dịch thương mại điện tử (Transactional Email Types).
 *
 * Chức năng:
 * - ORDER_CREATED: Thông báo xác nhận đặt hàng thành công khi kết thúc checkout.
 * - ORDER_PAID: Thông báo xác nhận thanh toán thành công và hóa đơn điện tử.
 * - ORDER_PAYMENT_FAILED: Cảnh báo thanh toán thất bại, nhắc nhở thanh toán lại trước khi hết hạn giữ kho 30 phút.
 */
public enum OrderEmailType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_PAYMENT_FAILED
}
