package com.nexus.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Lớp ngoại lệ cơ sở (Base Exception) của toàn bộ hệ thống Nexus.
 * Mang theo ErrorCode chuẩn, HTTP Status tương ứng, thông điệp tiếng Việt và đối tượng chi tiết (details).
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus httpStatus;
    private final Object details;

    public ApiException(ErrorCode errorCode) {
        super(errorCode.getDefaultMessage());
        this.code = errorCode.name();
        this.httpStatus = errorCode.getHttpStatus();
        this.details = null;
    }

    public ApiException(ErrorCode errorCode, String customMessage) {
        super(customMessage != null ? customMessage : errorCode.getDefaultMessage());
        this.code = errorCode.name();
        this.httpStatus = errorCode.getHttpStatus();
        this.details = null;
    }

    public ApiException(ErrorCode errorCode, String customMessage, Object details) {
        super(customMessage != null ? customMessage : errorCode.getDefaultMessage());
        this.code = errorCode.name();
        this.httpStatus = errorCode.getHttpStatus();
        this.details = details;
    }

    public ApiException(String code, HttpStatus httpStatus, String message, Object details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public Object getDetails() {
        return details;
    }
}
