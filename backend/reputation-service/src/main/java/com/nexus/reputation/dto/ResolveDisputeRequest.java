package com.nexus.reputation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Quyết định của Quản trị viên đối với một đánh giá đang bị gắn cờ khiếu nại.
 */
public record ResolveDisputeRequest(
        @NotNull(message = "Quyết định xử lý khiếu nại không được để trống")
        DisputeResolution resolution,
        @NotBlank(message = "Lý do xử lý khiếu nại không được để trống")
        @Size(max = 500, message = "Lý do tối đa 500 ký tự")
        String reason
) {

    /** DISMISS: bác bỏ khiếu nại, khôi phục đánh giá; INVALIDATE: hủy hiệu lực và hoàn tác điểm uy tín. */
    public enum DisputeResolution {
        DISMISS,
        INVALIDATE
    }
}
