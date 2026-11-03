package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Thông tin một mặt hàng SKU cần khóa giữ trong yêu cầu Checkout (SRS §3.6.1).
 *
 * @param skuId       Mã biến thể SKU
 * @param quantity    Số lượng cần giữ (tối thiểu là 1)
 * @param warehouseId Mã kho cụ thể (tùy chọn, nếu null dùng kho mặc định WH_001)
 */
public record ReservationItemRequest(
        @NotNull(message = "skuId không được để trống")
        UUID skuId,

        @NotNull(message = "Số lượng giữ không được để trống")
        @Min(value = 1, message = "Số lượng giữ tối thiểu phải là 1")
        Integer quantity,

        UUID warehouseId
) {
}
