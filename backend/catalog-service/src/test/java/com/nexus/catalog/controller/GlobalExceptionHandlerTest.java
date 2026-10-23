package com.nexus.catalog.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Kiểm thử tính năng xử lý lỗi toàn cục dùng chung từ nexus-common cho catalog-service theo Envelope Pattern v2.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resourceNotFoundMapsTo404() {
        var res = handler.handleResourceNotFound(new ResourceNotFoundException("missing resource"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("RESOURCE_NOT_FOUND", res.getBody().error().code());
    }

    @Test
    void businessMapsTo400() {
        var res = handler.handleBusiness(new BusinessException("bad input"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("BUSINESS_ERROR", res.getBody().error().code());
    }

    @Test
    void unexpectedMapsTo500() {
        var res = handler.handleUnexpected(new Exception("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("INTERNAL_SERVER_ERROR", res.getBody().error().code());
    }
}
