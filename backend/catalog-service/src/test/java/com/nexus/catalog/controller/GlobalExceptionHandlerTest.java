package com.nexus.catalog.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nexus.catalog.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void notFoundMapsTo404() {
        var res = handler.handleNotFound(new NotFoundException("missing"));
        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertEquals("NOT_FOUND", res.getBody().code());
    }

    @Test
    void businessMapsTo400() {
        var res = handler.handleBusiness(new com.nexus.catalog.exception.BusinessException("bad input"));
        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertEquals("BUSINESS_ERROR", res.getBody().code());
    }

    @Test
    void unexpectedMapsTo500() {
        var res = handler.handleUnexpected(new IllegalStateException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("INTERNAL_ERROR", res.getBody().code());
    }
}
