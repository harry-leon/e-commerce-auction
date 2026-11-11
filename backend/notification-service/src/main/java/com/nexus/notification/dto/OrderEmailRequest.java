package com.nexus.notification.dto;

import com.nexus.notification.enums.OrderEmailType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload yêu cầu gửi email xác nhận giao dịch và vòng đời đơn thắng đấu giá (SRS §3.2.3 & §5.2).
 * Hỗ trợ: ORDER_CREATED, ORDER_PAID, ORDER_PAYMENT_FAILED, AUCTION_WON,
 * AUCTION_PAYMENT_REMINDER (cảnh báo trước hạn 2h), và AUCTION_PAYMENT_TIMEOUT (quá hạn 24h & phạt uy tín).
 */
public record OrderEmailRequest(
        @NotNull(message = "Loại sự kiện không được để trống")
        OrderEmailType eventType,

        @NotNull(message = "Mã đơn hàng orderId không được để trống")
        UUID orderId,

        @NotBlank(message = "Mã hiển thị orderNo không được để trống")
        String orderNo,

        @NotNull(message = "Mã người mua userId không được để trống")
        UUID userId,

        @NotBlank(message = "Địa chỉ email người nhận không được để trống")
        @Email(message = "Địa chỉ email người nhận không hợp lệ")
        String recipientEmail,

        String recipientName,

        @NotNull(message = "Tổng tiền đơn hàng không được để trống")
        BigDecimal totalAmount,

        String paymentMethod,

        String failureReason,

        List<OrderItemEmailDto> items,

        // Key bắt buộc giúp worker retry sau timeout mà không tạo thêm email cho cùng sự kiện.
        @NotBlank(message = "Mã sự kiện không được để trống")
        @Size(max = 50)
        String eventId,

        UUID auctionId,

        Instant paymentDeadline,

        @Size(max = 500)
        String paymentLink,

        Integer penaltyScore
) {
    /** Constructor tương thích ngược với payload Sprint 1 gồm 11 trường. */
    public OrderEmailRequest(
            OrderEmailType eventType,
            UUID orderId,
            String orderNo,
            UUID userId,
            String recipientEmail,
            String recipientName,
            BigDecimal totalAmount,
            String paymentMethod,
            String failureReason,
            List<OrderItemEmailDto> items,
            String eventId) {
        this(eventType, orderId, orderNo, userId, recipientEmail, recipientName, totalAmount,
                paymentMethod, failureReason, items, eventId, null, null, null, null);
    }

    public static OrderEmailRequest ofOrderCreated(UUID orderId, String orderNo, UUID userId,
                                                   String email, String name, BigDecimal total,
                                                   List<OrderItemEmailDto> items, String eventId) {
        return new OrderEmailRequest(
                OrderEmailType.ORDER_CREATED, orderId, orderNo, userId, email, name, total,
                null, null, items, eventId
        );
    }

    public static OrderEmailRequest ofOrderPaid(UUID orderId, String orderNo, UUID userId,
                                                String email, String name, BigDecimal total,
                                                String paymentMethod, List<OrderItemEmailDto> items,
                                                String eventId) {
        return new OrderEmailRequest(
                OrderEmailType.ORDER_PAID, orderId, orderNo, userId, email, name, total,
                paymentMethod, null, items, eventId
        );
    }

    public static OrderEmailRequest ofPaymentFailed(UUID orderId, String orderNo, UUID userId,
                                                    String email, String name, BigDecimal total,
                                                    String failureReason, String eventId) {
        return new OrderEmailRequest(
                OrderEmailType.ORDER_PAYMENT_FAILED, orderId, orderNo, userId, email, name, total,
                null, failureReason, null, eventId
        );
    }

    /** Tạo payload email chúc mừng người thắng đấu giá kèm liên kết thanh toán và mốc deadline 24h. */
    public static OrderEmailRequest ofAuctionWon(UUID orderId, String orderNo, UUID userId,
                                                 String email, String name, BigDecimal total,
                                                 List<OrderItemEmailDto> items, String eventId,
                                                 UUID auctionId, Instant paymentDeadline, String paymentLink) {
        return new OrderEmailRequest(
                OrderEmailType.AUCTION_WON, orderId, orderNo, userId, email, name, total,
                "VNPAY", null, items, eventId, auctionId, paymentDeadline, paymentLink, null
        );
    }

    /** Tạo payload email cảnh báo nhắc nhở sắp hết hạn thanh toán đơn đấu giá (trước 2 giờ). */
    public static OrderEmailRequest ofAuctionPaymentReminder(UUID orderId, String orderNo, UUID userId,
                                                             String email, String name, BigDecimal total,
                                                             List<OrderItemEmailDto> items, String eventId,
                                                             UUID auctionId, Instant paymentDeadline,
                                                             String paymentLink, Integer penaltyScore) {
        return new OrderEmailRequest(
                OrderEmailType.AUCTION_PAYMENT_REMINDER, orderId, orderNo, userId, email, name, total,
                "VNPAY", null, items, eventId, auctionId, paymentDeadline, paymentLink, penaltyScore
        );
    }

    /** Tạo payload email thông báo hủy đơn đấu giá do quá hạn 24h và áp dụng trừ điểm uy tín. */
    public static OrderEmailRequest ofAuctionPaymentTimeout(UUID orderId, String orderNo, UUID userId,
                                                            String email, String name, BigDecimal total,
                                                            String failureReason, List<OrderItemEmailDto> items,
                                                            String eventId, UUID auctionId,
                                                            Instant paymentDeadline, Integer penaltyScore) {
        return new OrderEmailRequest(
                OrderEmailType.AUCTION_PAYMENT_TIMEOUT, orderId, orderNo, userId, email, name, total,
                "VNPAY", failureReason, items, eventId, auctionId, paymentDeadline, null, penaltyScore
        );
    }
}
