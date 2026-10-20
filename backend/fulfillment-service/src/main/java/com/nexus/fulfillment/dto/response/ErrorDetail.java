package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cấu trúc chi tiết lỗi theo chuẩn Error Response Pattern của hệ thống Nexus.
 *
 * Chức năng: Đóng gói thông tin lỗi máy đọc và người đọc khi API trả về phản hồi lỗi.
 *
 * @param code    Mã định danh lỗi máy đọc (VD: ERR_INSUFFICIENT_STOCK, ERR_SKU_NOT_FOUND)
 * @param message Thông điệp mô tả lỗi
 * @param details Chi tiết bổ sung (danh sách lỗi trường, dữ liệu vi phạm)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
        String code,
        String message,
        Object details
) {

    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(code, message, null);
    }

    public static ErrorDetail of(String code, String message, Object details) {
        return new ErrorDetail(code, message, details);
    }
}
