package com.nexus.catalog.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.nexus.catalog.entity.NotFoundException;
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
    void unexpectedMapsTo500() {
        var res = handler.handleUnexpected(new IllegalStateException("boom"));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.getStatusCode());
        assertEquals("INTERNAL_ERROR", res.getBody().code());
    }
}
