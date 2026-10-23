package com.nexus.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Ngoại lệ khi không tìm thấy tài nguyên trong hệ thống (HTTP 404 NOT_FOUND).
 * Hỗ trợ tạo từ ErrorCode chuẩn hoặc mã tùy biến tương thích ngược với code cũ.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super("RESOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, message, null);
    }

    public ResourceNotFoundException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(code, HttpStatus.NOT_FOUND, message, null);
    }
}
