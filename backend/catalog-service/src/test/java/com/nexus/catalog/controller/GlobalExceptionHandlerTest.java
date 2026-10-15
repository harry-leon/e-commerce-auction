package com.nexus.catalog.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.access.AccessDeniedException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resourceNotFoundMapsTo404() {
        var res = handler.handleResourceNotFound(new ResourceNotFoundException("missing resource"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals(false, res.getBody().success());
        assertEquals("RESOURCE_NOT_FOUND", res.getBody().error().code());
    }

    @Test
    void businessMapsTo400() {
        var res = handler.handleBusiness(new BusinessException("bad input"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals(false, res.getBody().success());
        assertEquals("BUSINESS_ERROR", res.getBody().error().code());
    }

    @Test
    void httpMessageNotReadableMapsTo400() {
        var ex = new HttpMessageNotReadableException("bad json", new MockHttpInputMessage(new byte[0]));
        var res = handler.handleHttpMessageNotReadable(ex);
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals(false, res.getBody().success());
        assertEquals("MALFORMED_JSON", res.getBody().error().code());
    }

    @Test
    void accessDeniedMapsTo403() {
        var res = handler.handleAccessDenied(new AccessDeniedException("denied"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals(false, res.getBody().success());
        assertEquals("FORBIDDEN", res.getBody().error().code());
    }

    @Test
    void unexpectedMapsTo500() {
        var res = handler.handleUnexpected(new IllegalStateException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals(false, res.getBody().success());
        assertEquals("INTERNAL_ERROR", res.getBody().error().code());
    }
}
