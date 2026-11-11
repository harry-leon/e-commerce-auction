package com.nexus.reputation.dto;

import com.nexus.reputation.enums.PenaltyRefType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Yêu cầu áp dụng trừ điểm uy tín idempotent cho người dùng vi phạm (mặc định 10 điểm khi quá hạn thanh toán đấu giá).
 */
public record ApplyPenaltyRequest(
        @NotNull(message = "Mã người dùng không được để trống")
        UUID userId,

        @NotNull(message = "Loại tham chiếu vi phạm không được để trống")
        PenaltyRefType refType,

        @NotNull(message = "Mã tham chiếu vi phạm (auctionId/orderId) không được để trống")
        UUID refId,

        @Min(value = 1, message = "Số điểm phạt phải lớn hơn hoặc bằng 1")
        @Max(value = 100, message = "Số điểm phạt tối đa mỗi lần là 100")
        Integer penaltyPoints,

        @NotBlank(message = "Lý do trừ điểm không được để trống")
        @Size(max = 500, message = "Lý do trừ điểm tối đa 500 ký tự")
        String reason
) {
}
