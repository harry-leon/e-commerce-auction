package com.nexus.commerce.dto.request;

import com.nexus.commerce.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO yêu cầu chuyển đổi trạng thái đơn hàng.
 *
 * @param toStatus Trạng thái mới muốn chuyển sang
 * @param reason   Lý do hoặc ghi chú thay đổi (ví dụ: "Người mua hủy đơn", "Đã bàn giao bưu cục")
 */
@Schema(description = "Yêu cầu chuyển đổi trạng thái đơn hàng")
public record OrderStatusChangeRequest(
        @NotNull(message = "Trạng thái đích không được để trống")
        @Schema(description = "Trạng thái mới của đơn hàng", example = "PROCESSING")
        OrderStatus toStatus,

        @Size(max = 500, message = "Lý do thay đổi không được vượt quá 500 ký tự")
        @Schema(description = "Lý do hoặc ghi chú chuyển trạng thái", example = "Người bán xác nhận và đóng gói đơn")
        String reason
) {
}
