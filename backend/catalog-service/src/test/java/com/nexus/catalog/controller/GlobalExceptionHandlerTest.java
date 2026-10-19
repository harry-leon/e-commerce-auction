package com.nexus.catalog.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;

/** Hồi quy hợp đồng ApiError: mã HTTP và code nằm trực tiếp trên response, không bọc envelope thành công. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resourceNotFoundMapsTo404() {
        var res = handler.handleResourceNotFound(new ResourceNotFoundException("missing resource"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("RESOURCE_NOT_FOUND", res.getBody().code());
    }

    @Test
    void businessMapsTo400() {
        var res = handler.handleBusiness(new BusinessException("bad input"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("BUSINESS_ERROR", res.getBody().code());
    }

    @Test
    void httpMessageNotReadableMapsTo400() {
        var ex = new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));
        var res = handler.handleHttpMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("MALFORMED_JSON", res.getBody().code());
    }

    @Test
    void accessDeniedMapsTo403() {
        var res = handler.handleAccessDenied(new AccessDeniedException("denied"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("FORBIDDEN", res.getBody().code());
    }

    @Test
    void unexpectedMapsTo500() {
        var res = handler.handleUnexpected(new IllegalStateException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("INTERNAL_ERROR", res.getBody().code());
    }
}
