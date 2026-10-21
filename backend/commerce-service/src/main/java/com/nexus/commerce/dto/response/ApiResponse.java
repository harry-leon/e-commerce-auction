package com.nexus.commerce.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Standard Envelope API response wrapper matching catalog-service specifications.
 *
 * @param success Whether the request succeeded
 * @param data    Response payload data
 * @param error   Error details when success is false
 * @param meta    Response metadata (timestamp, version, pagination, requestId)
 * @param <T>     Payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        ErrorDetail error,
        MetaInfo meta
) {

    /**
     * Successful response with payload data.
     */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, MetaInfo.of());
    }

    /**
     * Successful response without payload.
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null, MetaInfo.of());
    }

    /**
     * Successful response for Spring Data Page pagination.
     */
    public static <T> ApiResponse<List<T>> ok(Page<T> page) {
        return new ApiResponse<>(
                true,
                page.getContent(),
                null,
                MetaInfo.ofPagination(page.getNumber(), page.getSize(), page.getTotalElements())
        );
    }

    /**
     * Successful response for custom pagination data.
     */
    public static <T> ApiResponse<T> ok(T data, int page, int pageSize, long total) {
        return new ApiResponse<>(
                true,
                data,
                null,
                MetaInfo.ofPagination(page, pageSize, total)
        );
    }

    /**
     * Error response with machine code and message.
     */
    public static <T> ApiResponse<T> error(String code, String message) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message),
                MetaInfo.of()
        );
    }

    /**
     * Error response with machine code, message, and structured details.
     */
    public static <T> ApiResponse<T> error(String code, String message, Object details) {
        return new ApiResponse<>(
                false,
                null,
                ErrorDetail.of(code, message, details),
                MetaInfo.of()
        );
    }
}
