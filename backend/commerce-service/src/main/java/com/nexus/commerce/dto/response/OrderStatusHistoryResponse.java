package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO phản hồi một bản ghi lịch sử biến đổi trạng thái của đơn hàng (Audit Trail).
 *
 * @param id         ID định danh bản ghi lịch sử
 * @param orderId    ID đơn hàng
 * @param fromStatus Trạng thái trước khi chuyển
 * @param toStatus   Trạng thái sau khi chuyển
 * @param reason     Lý do hoặc ghi chú của sự kiện
 * @param changedBy  ID của người dùng/hệ thống thực hiện thay đổi
 * @param changedAt  Thời điểm diễn ra sự kiện
 */
@Schema(description = "Bản ghi lịch sử biến đổi trạng thái của đơn hàng")
public record OrderStatusHistoryResponse(
        @Schema(description = "ID bản ghi lịch sử")
        UUID id,

        @Schema(description = "ID đơn hàng")
        UUID orderId,

        @Schema(description = "Trạng thái trước đó", example = "PENDING_PAYMENT")
        OrderStatus fromStatus,

        @Schema(description = "Trạng thái mới", example = "PAID")
        OrderStatus toStatus,

        @Schema(description = "Lý do hoặc ghi chú", example = "Thanh toán thành công qua VNPAY")
        String reason,

        @Schema(description = "ID người thực hiện thay đổi")
        UUID changedBy,

        @Schema(description = "Thời gian thay đổi")
        Instant changedAt
) {
}
