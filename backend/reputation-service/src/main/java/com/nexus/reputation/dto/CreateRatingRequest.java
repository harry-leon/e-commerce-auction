package com.nexus.reputation.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload gửi đánh giá sau khi đơn hàng đã giao hoặc hoàn tất.
 * raterId được lấy trực tiếp từ JWT subject của người gọi để chống giả mạo danh tính.
 */
public record CreateRatingRequest(
        @NotNull(message = "Mã đơn hàng không được để trống")
        UUID orderId,
        @NotNull(message = "Mã người được đánh giá không được để trống")
        UUID rateeId,
        @Min(value = 1, message = "Điểm đánh giá tối thiểu là 1 sao")
        @Max(value = 5, message = "Điểm đánh giá tối đa là 5 sao")
        int score,
        @Size(max = 1000, message = "Nhận xét tối đa 1000 ký tự")
        String comment
) {
}
