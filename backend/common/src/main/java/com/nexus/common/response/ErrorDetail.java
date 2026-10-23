package com.nexus.common.response;

/**
 * Cấu trúc chi tiết lỗi chuẩn theo quy chuẩn Envelope v2.
 * Bắt buộc có đủ 3 thuộc tính: code, message, details.
 *
 * @param code    Mã định danh lỗi dạng MODULE_ERROR_CODE viết hoa (UPPER_SNAKE_CASE)
 * @param message Thông điệp lỗi giải thích (ngôn ngữ theo Accept-Language, mặc định tiếng Việt)
 * @param details Chi tiết bổ sung: null (lỗi nghiệp vụ thông thường), Object (dữ liệu kèm theo),
 *                hoặc mảng List&lt;ValidationErrorDetail&gt; (khi code là VALIDATION_ERROR)
 */
public record ErrorDetail(
        String code,
        String message,
        Object details
) {

    /**
     * Tạo ErrorDetail không có dữ liệu chi tiết kèm theo (details = null).
     */
    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(code, message, null);
    }

    /**
     * Tạo ErrorDetail kèm dữ liệu chi tiết (Object ngữ cảnh hoặc List lỗi validation).
     */
    public static ErrorDetail of(String code, String message, Object details) {
        return new ErrorDetail(code, message, details);
    }
}
