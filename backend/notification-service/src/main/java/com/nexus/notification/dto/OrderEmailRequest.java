package com.nexus.notification.dto;

import com.nexus.notification.enums.OrderEmailType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Payload yêu cầu gửi email xác nhận giao dịch thương mại điện tử (SRS §3.2.3 & §5.2).
 *
 * Chức năng:
 * - Tiếp nhận các sự kiện OrderCreated, OrderPaid, OrderPaymentFailed từ các service Commerce / Fulfillment.
 * - Mang thông tin người mua, đơn hàng, hóa đơn thanh toán và danh mục mặt hàng đã mua.
 *
 * Quy tắc Idempotent:
 * - Trường eventId được dùng để phát hiện và ngăn ngừa gửi lặp email khi nhận duplicate webhook / request.
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

        String eventId
) {
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
}
