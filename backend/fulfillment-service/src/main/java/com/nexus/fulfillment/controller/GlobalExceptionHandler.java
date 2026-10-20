package com.nexus.fulfillment.controller;

import com.nexus.fulfillment.entity.NotFoundException;
import com.nexus.fulfillment.exception.InventoryBusinessException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Xử lý ngoại lệ tập trung (Global Exception Handler) cho Fulfillment Service.
 *
 * Chức năng: Bắt mọi Exception trong controller, chuẩn hóa về cấu trúc ApiError {code, message, details, timestamp}
 * theo thỏa thuận kiến trúc chung của toàn hệ thống (docs/CODE_GUIDE.md & SRS §4.2).
 *
 * Các nhóm ngoại lệ được xử lý:
 * 1. InventoryBusinessException (và các lớp con InsufficientStockException, ReservationExpiredException, SkuNotFoundException...):
 *    Trả đúng mã HTTP tương ứng (409, 410, 404, 400) và mã lỗi nghiệp vụ máy đọc.
 * 2. MethodArgumentNotValidException: Lỗi validation dữ liệu đầu vào Bean Validation (@Valid).
 * 3. MethodArgumentTypeMismatchException: Lỗi định dạng tham số đường dẫn/query (UUID sai chuẩn).
 * 4. HttpMessageNotReadableException: Lỗi parse body JSON không hợp lệ.
 * 5. AccessDeniedException: Lỗi từ chối phân quyền (HTTP 403 Forbidden).
 * 6. NotFoundException: Lỗi không tìm thấy tài nguyên chung (HTTP 404 Not Found).
 * 7. Exception: Ngoại lệ không xác định (HTTP 500 Internal Server Error).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Xử lý các ngoại lệ nghiệp vụ tồn kho (InventoryBusinessException).
     * Trả về đúng mã lỗi (ERR_INSUFFICIENT_STOCK, ERR_RESERVATION_EXPIRED, ERR_SKU_NOT_FOUND...)
     * cùng HTTP status tương ứng (409, 410, 404, 400).
     */
    @ExceptionHandler(InventoryBusinessException.class)
    public ResponseEntity<ApiError> handleInventoryBusiness(InventoryBusinessException ex) {
        LOG.warn("Inventory business error [{}]: {}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(ex.getHttpStatus())
                .body(ApiError.of(ex.getCode(), ex.getMessage(), ex.getDetails()));
    }

    /**
     * Xử lý lỗi validation từ Spring Bean Validation (@Valid @RequestBody).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + (e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid value"))
                .toList();
        LOG.warn("Validation error: {}", details);
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", "Validation failed for request arguments", details));
    }

    /**
     * Xử lý lỗi sai kiểu tham số query hoặc path variable (ví dụ: chuỗi không phải UUID hợp lệ).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        LOG.warn("Invalid parameter type for: {}", paramName);
        return ResponseEntity.badRequest()
                .body(ApiError.of("INVALID_PARAMETER", "Invalid parameter: " + paramName));
    }

    /**
     * Xử lý lỗi định dạng payload JSON gửi lên bị sai cú pháp.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException ex) {
        LOG.warn("Malformed JSON request received: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiError.of("MALFORMED_JSON", "Malformed JSON request body or invalid data format"));
    }

    /**
     * Xử lý lỗi phân quyền (Access Denied).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        LOG.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", "Access denied"));
    }

    /**
     * Xử lý ngoại lệ NotFoundException thông thường.
     */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage()));
    }

    /**
     * Fallback cho tất cả các ngoại lệ không lường trước.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        LOG.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
