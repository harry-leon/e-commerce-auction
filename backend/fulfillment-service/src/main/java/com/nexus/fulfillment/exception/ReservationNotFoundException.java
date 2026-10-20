package com.nexus.fulfillment.exception;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lỗi phát sinh khi không tìm thấy phiếu giữ hàng.
 *
 * Chức năng: Báo lỗi khi tra cứu, commit hoặc release một reservation không tồn tại trong hệ thống.
 * Mã lỗi: ERR_RESERVATION_NOT_FOUND
 * HTTP Status: 404 NOT_FOUND
 */
public class ReservationNotFoundException extends InventoryBusinessException {

    public ReservationNotFoundException(String message) {
        super(InventoryErrorCode.ERR_RESERVATION_NOT_FOUND, message, HttpStatus.NOT_FOUND);
    }

    public ReservationNotFoundException(UUID reservationId) {
        super(
                InventoryErrorCode.ERR_RESERVATION_NOT_FOUND,
                String.format("Không tìm thấy phiếu giữ hàng với ID: %s", reservationId),
                HttpStatus.NOT_FOUND,
                List.of(String.format("reservationId: %s", reservationId))
        );
    }
}
