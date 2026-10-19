package com.nexus.catalog.controller;

import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.ResourceNotFoundException;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Xử lý ngoại lệ tập trung (Global Exception Handler) cho catalog-service
 * tuân thủ theo danh sách Common Exceptions:
 * - ResourceNotFoundException (custom)
 * - MethodArgumentNotValidException (validation)
 * - HttpMessageNotReadableException (JSON parse errors)
 * - AccessDeniedException (security)
 * - BusinessException (custom business rules)
 * - Exception (fallback)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Tham số query/path sai kiểu (UUID, giá, enum...) là lỗi client 400, không phải lỗi hệ thống 500. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleInvalidParameter(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_PARAMETER", "Invalid parameter: " + ex.getName()));
    }

    /**
     * 1. ResourceNotFoundException (custom 404)
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    /**
     * 2. BusinessException (custom 400 cho logic nghiệp vụ)
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of(ex.getCode(), ex.getMessage()));
    }

    /**
     * 3. MethodArgumentNotValidException (validation bean validation 400)
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream().collect(Collectors.toMap(
                FieldError::getField, e -> e.getDefaultMessage() != null ? e.getDefaultMessage() : "Invalid value", (existing, replacement) -> existing));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("VALIDATION_ERROR", "Validation failed for request arguments", fieldErrors.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue()).toList()));
    }

    /**
     * 4. HttpMessageNotReadableException (JSON parse errors / malformed body 400)
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        LOG.warn("Malformed JSON request received: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError.of("MALFORMED_JSON", "Malformed JSON request body or invalid data format"));
    }

    /**
     * 5. AccessDeniedException (security 403 Forbidden)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of("FORBIDDEN", "Access denied"));
    }

    /**
     * 6. Exception (fallback 500 Internal Server Error)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        LOG.error("Unexpected error occurred", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
