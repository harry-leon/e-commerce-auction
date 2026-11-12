package com.nexus.reputation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Yêu cầu Admin điều chỉnh điểm uy tín thủ công (cộng hoặc trừ) kèm lý do kiểm toán bắt buộc.
 */
public record AdminAdjustReputationRequest(
        @NotNull(message = "Mã người dùng không được để trống")
        UUID userId,
        @Min(value = -200, message = "Mức điều chỉnh điểm tối thiểu là -200")
        @Max(value = 200, message = "Mức điều chỉnh điểm tối đa là +200")
        int scoreDelta,
        @NotBlank(message = "Lý do điều chỉnh uy tín không được để trống")
        @Size(max = 500, message = "Lý do điều chỉnh tối đa 500 ký tự")
        String reason,
        UUID refId
) {
}
