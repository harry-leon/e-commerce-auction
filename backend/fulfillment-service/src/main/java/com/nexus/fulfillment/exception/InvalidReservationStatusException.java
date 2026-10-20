package com.nexus.fulfillment.exception;

import com.nexus.fulfillment.enums.ReservationStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lỗi phát sinh khi trạng thái hiện tại của phiếu giữ hàng không hợp lệ cho thao tác được yêu cầu.
 *
 * Chức năng: Ngăn chặn các chuyển đổi trạng thái phi logic (ví dụ: commit một phiếu đã RELEASED hoặc đã CONFIRMED).
 * Mã lỗi: ERR_INVALID_RESERVATION_STATUS
 * HTTP Status: 400 BAD_REQUEST
 */
public class InvalidReservationStatusException extends InventoryBusinessException {

    public InvalidReservationStatusException(String message) {
        super(InventoryErrorCode.ERR_INVALID_RESERVATION_STATUS, message, HttpStatus.BAD_REQUEST);
    }

    public InvalidReservationStatusException(UUID reservationId, ReservationStatus currentStatus, String action) {
        super(
                InventoryErrorCode.ERR_INVALID_RESERVATION_STATUS,
                String.format("Không thể thực hiện '%s' trên phiếu giữ hàng %s vì đang ở trạng thái %s", action, reservationId, currentStatus),
                HttpStatus.BAD_REQUEST,
                List.of(String.format("reservationId: %s, currentStatus: %s, requestedAction: %s", reservationId, currentStatus, action))
        );
    }
}
