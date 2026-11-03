package com.nexus.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Chi tiết lỗi kiểm tra tính hợp lệ dữ liệu của từng field trong request (Field Validation Error).
 * Chỉ xuất hiện trong mảng error.details của mã lỗi VALIDATION_ERROR (HTTP 422).
 *
 * @param field   Tên field bị vi phạm (hỗ trợ dot/bracket notation: items[0].quantity, address.districtId)
 * @param code    Mã lỗi validation chuẩn (NOT_BLANK, MIN_VALUE, MUST_BE_POSITIVE, ...)
 * @param message Thông điệp giải thích lỗi bằng tiếng Việt
 * @param params  Tham số ràng buộc kèm theo (ví dụ: { "min": 1 }, { "max": 100 })
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidationErrorDetail(
        String field,
        String code,
        String message,
        Map<String, Object> params
) {

    /**
     * Tạo chi tiết lỗi validation không kèm tham số ràng buộc.
     */
    public static ValidationErrorDetail of(String field, String code, String message) {
        return new ValidationErrorDetail(field, code, message, null);
    }

    /**
     * Tạo chi tiết lỗi validation kèm tham số ràng buộc bổ sung.
     */
    public static ValidationErrorDetail of(String field, String code, String message, Map<String, Object> params) {
        return new ValidationErrorDetail(field, code, message, params);
    }
}
