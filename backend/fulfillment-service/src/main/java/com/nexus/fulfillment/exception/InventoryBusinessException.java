package com.nexus.fulfillment.exception;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Exception cơ sở cho toàn bộ lỗi nghiệp vụ trong Fulfillment Service.
 *
 * Chức năng: Lưu giữ mã lỗi máy đọc (machine-readable code), mã HTTP tương ứng và danh sách chi tiết lỗi
 * để GlobalExceptionHandler chuyển đổi thành ApiError trả về client đồng bộ với toàn hệ thống.
 *
 * Đầu vào:
 * - code: Chuỗi định danh mã lỗi (ví dụ ERR_INSUFFICIENT_STOCK).
 * - message: Thông điệp mô tả lỗi.
 * - httpStatus: Trạng thái HTTP status code.
 * - details: Danh sách chi tiết các item lỗi hoặc lý do vi phạm.
 */
public class InventoryBusinessException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final List<String> details;

    public InventoryBusinessException(String code, String message, HttpStatus httpStatus) {
        this(code, message, httpStatus, List.of());
    }

    public InventoryBusinessException(String code, String message, HttpStatus httpStatus, List<String> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus != null ? httpStatus : HttpStatus.BAD_REQUEST;
        this.details = details != null ? List.copyOf(details) : List.of();
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public List<String> getDetails() {
        return details;
    }
}
