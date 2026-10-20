package com.nexus.fulfillment.exception;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lỗi phát sinh khi thực hiện thao tác trên phiếu giữ hàng đã hết hạn.
 *
 * Chức năng: Ngăn chặn việc xác nhận xuất kho (commit) trên phiếu giữ hàng đã quá hạn `expires_at`.
 * Mã lỗi: ERR_RESERVATION_EXPIRED
 * HTTP Status: 410 GONE
 * Luồng xử lý: Khi gặp lỗi này, Commerce Service cần hủy đơn hoặc yêu cầu người dùng checkout lại từ đầu.
 */
public class ReservationExpiredException extends InventoryBusinessException {

    public ReservationExpiredException(String message) {
        super(InventoryErrorCode.ERR_RESERVATION_EXPIRED, message, HttpStatus.GONE);
    }

    public ReservationExpiredException(UUID reservationId, Instant expiresAt) {
        super(
                InventoryErrorCode.ERR_RESERVATION_EXPIRED,
                String.format("Phiếu giữ hàng %s đã hết hạn vào lúc %s", reservationId, expiresAt),
                HttpStatus.GONE,
                List.of(String.format("reservationId: %s, expiredAt: %s", reservationId, expiresAt))
        );
    }
}
