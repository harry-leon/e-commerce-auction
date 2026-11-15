package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * DTO nhận yêu cầu giải quyết tranh chấp ký quỹ đơn hàng từ Admin.
 *
 * <p>Chức năng: Chuyển giao phán quyết xử lý quỹ tiền ký quỹ (hoàn tiền người mua, giải ngân người bán, hoặc hoàn một phần).
 *
 * <p>Đầu vào và kết quả:
 * <ul>
 *   <li>resolution: 'refunded' (hoàn 100% cho buyer), 'released' (giải ngân cho seller), 'partial_refund' (hoàn một phần).</li>
 *   <li>note: Lý do hoặc ghi chú giải quyết tranh chấp của Admin (tối đa 500 ký tự).</li>
 * </ul>
 *
 * <p>Quy tắc và lý do:
 * Ràng buộc regex không phân biệt hoa thường để tiếp nhận chuỗi định danh phán quyết từ Admin Portal FE.
 * Giới hạn 500 ký tự phù hợp với độ dài cột reason trong bảng order_status_history.
 *
 * <p>Lỗi và giới hạn:
 * Ném ngoại lệ validation nếu resolution rỗng hoặc không thuộc danh sách cho phép, hoặc note vượt quá 500 ký tự.
 * Việc parse được refunded/partial_refund không cấp quyền hoàn tiền: service trả lỗi nghiệp vụ cho hai lựa chọn này,
 * vì request không có bằng chứng provider; dùng workflow refund/đối soát riêng, không tự hoàn kho.
 */
public record ResolveOrderDisputeRequest(
        @NotBlank(message = "Nghị quyết tranh chấp không được để trống")
        @Pattern(regexp = "(?i)^(refunded|released|partial_refund)$",
                message = "Nghị quyết không hợp lệ (chỉ chấp nhận 'refunded', 'released' hoặc 'partial_refund')")
        String resolution,

        @Size(max = 500, message = "Ghi chú giải quyết tranh chấp không được vượt quá 500 ký tự")
        String note
) {
}

