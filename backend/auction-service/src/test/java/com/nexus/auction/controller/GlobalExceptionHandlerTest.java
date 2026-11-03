package com.nexus.auction.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Kiểm thử tính năng xử lý lỗi toàn cục dùng chung từ nexus-common cho auction-service.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        var res = handler.handleResourceNotFound(new ResourceNotFoundException("missing"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("RESOURCE_NOT_FOUND", res.getBody().error().code());
    }

    @Test
    void unexpectedMapsToConflictOr500() {
        var res = handler.handleIllegalState(new IllegalStateException("boom"));
        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
    }
}
