package com.nexus.commerce.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        var res = handler.handleResourceNotFound(new ResourceNotFoundException("missing"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("RESOURCE_NOT_FOUND", res.getBody().error().code());
        assertEquals("missing", res.getBody().error().message());
    }

    @Test
    void businessExceptionMapsTo400() {
        var res = handler.handleBusiness(new BusinessException("INSUFFICIENT_STOCK", "Out of stock"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertNotNull(res.getBody());
        assertFalse(res.getBody().success());
        assertEquals("INSUFFICIENT_STOCK", res.getBody().error().code());
        assertEquals("Out of stock", res.getBody().error().message());
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
