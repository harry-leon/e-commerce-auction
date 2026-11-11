package com.nexus.notification.enums;

/**
 * Loại sự kiện email giao dịch thương mại điện tử (Transactional Email Types).
 *
 * Chức năng:
 * - ORDER_CREATED: Thông báo xác nhận đặt hàng thành công khi kết thúc checkout.
 * - ORDER_PAID: Thông báo xác nhận thanh toán thành công và hóa đơn điện tử.
 * - ORDER_PAYMENT_FAILED: Cảnh báo thanh toán thất bại, nhắc nhở thanh toán lại trước khi hết hạn giữ kho 30 phút.
 * - AUCTION_WON: Chúc mừng người thắng đấu giá kèm liên kết thanh toán và mốc hạn 24 giờ.
 * - AUCTION_PAYMENT_REMINDER: Cảnh báo nhắc nhở sắp hết hạn thanh toán đơn đấu giá (trước 2 giờ).
 * - AUCTION_PAYMENT_TIMEOUT: Thông báo đơn đấu giá bị hủy do quá hạn 24h, giải phóng kho và phạt uy tín.
 */
public enum OrderEmailType {
    ORDER_CREATED,
    ORDER_PAID,
    ORDER_PAYMENT_FAILED,
    AUCTION_WON,
    AUCTION_PAYMENT_REMINDER,
    AUCTION_PAYMENT_TIMEOUT
}
