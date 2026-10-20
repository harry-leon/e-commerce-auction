package com.nexus.fulfillment.exception;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Lỗi phát sinh khi không tìm thấy SKU trong hệ thống kho.
 *
 * Chức năng: Báo lỗi khi client hoặc service khác yêu cầu kiểm tra tồn kho hoặc giữ hàng cho SKU không tồn tại.
 * Mã lỗi: ERR_SKU_NOT_FOUND
 * HTTP Status: 404 NOT_FOUND
 */
public class SkuNotFoundException extends InventoryBusinessException {

    public SkuNotFoundException(String message) {
        super(InventoryErrorCode.ERR_SKU_NOT_FOUND, message, HttpStatus.NOT_FOUND);
    }

    public SkuNotFoundException(UUID skuId) {
        super(
                InventoryErrorCode.ERR_SKU_NOT_FOUND,
                String.format("Không tìm thấy thông tin tồn kho cho SKU: %s", skuId),
                HttpStatus.NOT_FOUND,
                List.of(String.format("skuId: %s", skuId))
        );
    }
}
