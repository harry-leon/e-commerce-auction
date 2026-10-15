package com.nexus.catalog.exception;

/**
 * Lỗi nghiệp vụ (Business logic exception), ánh xạ HTTP 400 Bad Request.
 */
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        super(message);
        this.code = "BUSINESS_ERROR";
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
