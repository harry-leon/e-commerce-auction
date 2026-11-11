package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu đọc hàng loạt 4 trạng thái tồn kho cho danh sách SKU tại một kho chỉ định hoặc kho mặc định.
 *
 * @param skuIds      Danh sách mã biến thể SKU cần đọc tồn kho
 * @param warehouseId Mã kho cần đọc (tùy chọn, mặc định WH_001)
 */
public record BatchStockRequest(
        @NotEmpty(message = "Danh sách skuIds không được để trống")
        List<UUID> skuIds,

        UUID warehouseId
) {
}
