package com.nexus.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Ngoại lệ vi phạm quy tắc nghiệp vụ (Business Rule Violation).
 * Mặc định sử dụng HTTP status từ ErrorCode (hoặc 400/409/422 tùy mã nghiệp vụ).
 */
public class BusinessException extends ApiException {

    public BusinessException(String message) {
        super("BUSINESS_ERROR", HttpStatus.BAD_REQUEST, message, null);
    }

    public BusinessException(ErrorCode errorCode) {
        super(errorCode);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public BusinessException(ErrorCode errorCode, String message, Object details) {
        super(errorCode, message, details);
    }

    public BusinessException(String code, String message) {
        super(code, HttpStatus.BAD_REQUEST, message, null);
    }

    public BusinessException(String code, String message, Object details) {
        super(code, HttpStatus.BAD_REQUEST, message, details);
    }
}
