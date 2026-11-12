package com.nexus.reputation.dto;

import jakarta.validation.constraints.Size;

/**
 * Payload tùy chọn chứa lý do khi người nhận đánh giá hoặc Admin/Support gắn cờ khiếu nại đánh giá.
 */
public record DisputeRatingRequest(
        @Size(max = 500, message = "Lý do khiếu nại tối đa 500 ký tự")
        String reason
) {
}
