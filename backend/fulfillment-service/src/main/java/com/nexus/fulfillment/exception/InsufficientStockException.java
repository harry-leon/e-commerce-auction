package com.nexus.fulfillment.exception;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lỗi phát sinh khi tồn kho khả dụng không đủ đáp ứng số lượng yêu cầu.
 *
 * Chức năng: Báo lỗi khi kiểm tra tồn hoặc tạo phiếu giữ hàng mà tồn kho (total - reserved - unavailable) < requested_qty.
 * Mã lỗi: ERR_INSUFFICIENT_STOCK
 * HTTP Status: 409 CONFLICT (hoặc 400 BAD_REQUEST khi gọi API kiểm tra)
 * Quy tắc: Tuân thủ BR-CFG-32 & BR-INV-03 không bao giờ cho phép bán vượt tồn (ALLOW_OVERSELL = false).
 */
public class InsufficientStockException extends InventoryBusinessException {

    public InsufficientStockException(String message) {
        super(InventoryErrorCode.ERR_INSUFFICIENT_STOCK, message, HttpStatus.CONFLICT);
    }

    public InsufficientStockException(String message, List<String> details) {
        super(InventoryErrorCode.ERR_INSUFFICIENT_STOCK, message, HttpStatus.CONFLICT, details);
    }

    public InsufficientStockException(UUID skuId, int requestedQty, int availableQty) {
        super(
                InventoryErrorCode.ERR_INSUFFICIENT_STOCK,
                String.format("Không đủ tồn kho khả dụng cho SKU %s (Yêu cầu: %d, Khả dụng: %d)", skuId, requestedQty, availableQty),
                HttpStatus.CONFLICT,
                List.of(String.format("skuId: %s, requested: %d, available: %d", skuId, requestedQty, availableQty))
        );
    }
}
