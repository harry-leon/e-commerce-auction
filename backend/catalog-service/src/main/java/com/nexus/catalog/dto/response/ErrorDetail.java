package com.nexus.catalog.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Cấu trúc chi tiết lỗi theo chuẩn Error Response Pattern.
 *
 * @param code    Mã định danh lỗi máy đọc (VD: USER_NOT_FOUND, INVALID_INPUT)
 * @param message Thông điệp lỗi người đọc
 * @param details Chi tiết bổ sung (VD: Map validation field lỗi hoặc context object)
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
