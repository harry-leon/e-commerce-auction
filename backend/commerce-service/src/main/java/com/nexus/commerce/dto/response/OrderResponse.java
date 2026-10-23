package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO phản hồi thông tin chi tiết một đơn hàng hoàn chỉnh.
 *
 * <p>Bao gồm thông tin tổng quan, địa chỉ người nhận, tổng số tiền,
 * danh sách các sản phẩm và tiến trình lịch sử trạng thái đơn hàng.
 */
@Schema(description = "Thông tin chi tiết đơn hàng")
public record OrderResponse(
        @Schema(description = "ID đơn hàng")
        UUID id,

        @Schema(description = "Mã đơn hàng hiển thị", example = "ORD-20261023-8A2F1C")
        String orderNo,

        @Schema(description = "ID người mua")
        UUID buyerId,

        @Schema(description = "ID người bán")
        UUID sellerId,

        @Schema(description = "Nguồn gốc đơn hàng", example = "REGULAR")
        OrderSourceType sourceType,

        @Schema(description = "Mã phiên đấu giá (nếu có)")
        UUID auctionId,

        @Schema(description = "Mã voucher (nếu có)")
        UUID voucherId,

        @Schema(description = "Trạng thái đơn hàng hiện tại", example = "PENDING_PAYMENT")
        OrderStatus status,

        @Schema(description = "Tổng tiền hàng trước giảm giá và ship", example = "5800000.00")
        BigDecimal subtotal,

        @Schema(description = "Phí vận chuyển", example = "35000.00")
        BigDecimal shippingFee,

        @Schema(description = "Số tiền được giảm giá", example = "200000.00")
        BigDecimal discount,

        @Schema(description = "Tổng số tiền thanh toán thực tế", example = "5635000.00")
        BigDecimal total,

        @Schema(description = "Địa chỉ nhận hàng chi tiết", example = "Số 123 Lê Lợi, Bến Nghé, Quận 1, TP. Hồ Chí Minh")
        String shippingAddress,

        @Schema(description = "Tên người nhận", example = "Nguyễn Văn A")
        String recipientName,

        @Schema(description = "Số điện thoại người nhận", example = "0901234567")
        String recipientPhone,

        @Schema(description = "Khóa chống trùng lặp Idempotency-Key")
        String idempotencyKey,

        @Schema(description = "Thời gian tạo đơn")
        Instant createdAt,

        @Schema(description = "Danh sách các mặt hàng trong đơn")
        List<OrderItemResponse> items,

        @Schema(description = "Lịch sử biến đổi trạng thái của đơn")
        List<OrderStatusHistoryResponse> statusHistories
) {
}
