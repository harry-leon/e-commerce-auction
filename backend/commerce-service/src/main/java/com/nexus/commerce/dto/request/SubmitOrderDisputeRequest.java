package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO nhận yêu cầu mở khiếu nại tranh chấp đơn hàng từ Người mua.
 *
 * <p>Chức năng: Tiếp nhận lý do khiếu nại từ người mua khi phát sinh sự cố về đơn hàng hoặc hàng giao lỗi.
 *
 * <p>Đầu vào và kết quả:
 * <ul>
 *   <li>reason: Lý do hoặc mô tả khiếu nại từ người mua (bắt buộc, tối đa 500 ký tự).</li>
 * </ul>
 *
 * <p>Quy tắc và lý do:
 * Lý do khiếu nại được ghi nhận vào lịch sử kiểm toán của đơn hàng để Quản trị viên xem xét giải quyết ký quỹ.
 * Giới hạn 500 ký tự phù hợp với độ dài cột reason trong bảng order_status_history.
 *
 * <p>Lỗi và giới hạn:
 * Ném ngoại lệ validation nếu reason rỗng hoặc vượt quá 500 ký tự.
 */
public record SubmitOrderDisputeRequest(
        @NotBlank(message = "Lý do khiếu nại không được để trống")
        @Size(max = 500, message = "Lý do khiếu nại không được vượt quá 500 ký tự")
        String reason
) {
}

