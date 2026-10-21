package com.nexus.commerce.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Detailed error information conforming to the Project Nexus Error Response Pattern.
 *
 * @param code    Machine-readable error code (e.g. RESOURCE_NOT_FOUND, INVALID_PARAMETER)
 * @param message Human-readable error message
 * @param details Additional context or field-level validation details
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorDetail(
        String code,
        String message,
        Object details
) {

    public static ErrorDetail of(String code, String message) {
        return new ErrorDetail(code, message, null);
    }

    public static ErrorDetail of(String code, String message, Object details) {
        return new ErrorDetail(code, message, details);
    }
}
