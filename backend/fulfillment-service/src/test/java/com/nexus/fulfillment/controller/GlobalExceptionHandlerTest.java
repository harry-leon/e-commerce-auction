package com.nexus.fulfillment.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.nexus.fulfillment.entity.NotFoundException;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.exception.InsufficientStockException;
import com.nexus.fulfillment.exception.InventoryErrorCode;
import com.nexus.fulfillment.exception.InvalidReservationStatusException;
import com.nexus.fulfillment.exception.ReservationExpiredException;
import com.nexus.fulfillment.exception.ReservationNotFoundException;
import com.nexus.fulfillment.exception.SkuNotFoundException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

/**
 * Kiểm thử đơn vị cho GlobalExceptionHandler của Fulfillment Service.
 *
 * Chức năng: Đảm bảo toàn bộ các mã lỗi nghiệp vụ tồn kho (ERR_INSUFFICIENT_STOCK, ERR_RESERVATION_EXPIRED,
 * ERR_SKU_NOT_FOUND, ERR_RESERVATION_NOT_FOUND, ERR_INVALID_RESERVATION_STATUS) được map chính xác
 * sang ApiError với HTTP status code tương ứng theo quy chuẩn [KH-S1-1].
 */
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

    @Test
    void insufficientStockMapsTo409AndErrorCode() {
        UUID skuId = UUID.randomUUID();
        var ex = new InsufficientStockException(skuId, 10, 2);
        var res = handler.handleInventoryBusiness(ex);

        assertEquals(HttpStatus.CONFLICT, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(InventoryErrorCode.ERR_INSUFFICIENT_STOCK, res.getBody().code());
        assertFalse(res.getBody().details().isEmpty());
    }

    @Test
    void reservationExpiredMapsTo410AndErrorCode() {
        UUID reservationId = UUID.randomUUID();
        var ex = new ReservationExpiredException(reservationId, Instant.now());
        var res = handler.handleInventoryBusiness(ex);

        assertEquals(HttpStatus.GONE, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(InventoryErrorCode.ERR_RESERVATION_EXPIRED, res.getBody().code());
    }

    @Test
    void skuNotFoundMapsTo404AndErrorCode() {
        UUID skuId = UUID.randomUUID();
        var ex = new SkuNotFoundException(skuId);
        var res = handler.handleInventoryBusiness(ex);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(InventoryErrorCode.ERR_SKU_NOT_FOUND, res.getBody().code());
    }

    @Test
    void reservationNotFoundMapsTo404AndErrorCode() {
        UUID resId = UUID.randomUUID();
        var ex = new ReservationNotFoundException(resId);
        var res = handler.handleInventoryBusiness(ex);

        assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(InventoryErrorCode.ERR_RESERVATION_NOT_FOUND, res.getBody().code());
    }

    @Test
    void invalidReservationStatusMapsTo400AndErrorCode() {
        UUID resId = UUID.randomUUID();
        var ex = new InvalidReservationStatusException(resId, ReservationStatus.CONFIRMED, "RELEASE");
        var res = handler.handleInventoryBusiness(ex);

        assertEquals(HttpStatus.BAD_REQUEST, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(InventoryErrorCode.ERR_INVALID_RESERVATION_STATUS, res.getBody().code());
    }

    @Test
    void accessDeniedMapsTo403() {
        var res = handler.handleAccessDenied(new AccessDeniedException("Access denied"));
        assertEquals(HttpStatus.FORBIDDEN, res.getStatusCode());
        assertEquals("FORBIDDEN", res.getBody().code());
    }
}
