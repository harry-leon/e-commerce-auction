package com.nexus.catalog.controller;

import java.time.Instant;
import java.util.List;

/**
 * Hợp đồng lỗi chung theo CONTRIBUTING: code/message/details/timestamp, giống các service khác.
 * Chỉ dùng cho HTTP lỗi; response thành công của Catalog vẫn giữ ApiResponse và metadata phân trang.
 */
public record ApiError(String code, String message, List<String> details, Instant timestamp) {
    /** Tạo lỗi không có danh sách validation; timestamp do server sinh, không nhận từ request. */
    public static ApiError of(String code, String message) {
        return of(code, message, List.of());
    }

    /** Trả các lỗi field dưới dạng danh sách để client không phải xử lý nhiều hình dạng details khác nhau. */
    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, details, Instant.now());
    }
}
