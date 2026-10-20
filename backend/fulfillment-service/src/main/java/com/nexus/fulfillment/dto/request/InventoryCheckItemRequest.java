package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Mục hàng SKU cần kiểm tra tồn kho trong yêu cầu kiểm tra hàng loạt.
 *
 * Chức năng: Tiếp nhận thông tin mã SKU, mã kho (nếu chỉ định cụ thể) và số lượng muốn mua.
 *
 * @param skuId       Mã SKU cần kiểm tra (bắt buộc)
 * @param warehouseId Mã kho hàng (tùy chọn; nếu null sẽ kiểm tra kho mặc định hoặc tổng thể)
 * @param quantity    Số lượng cần kiểm tra (bắt buộc >= 1)
 */
@Schema(description = "Thông tin SKU và số lượng cần kiểm tra tồn kho")
public record InventoryCheckItemRequest(
        @NotNull(message = "sku_id is required")
        @Schema(description = "Mã SKU cần kiểm tra", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID skuId,

        @Schema(description = "Mã kho hàng cụ thể (tùy chọn)", example = "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
        UUID warehouseId,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be greater than 0")
        @Schema(description = "Số lượng yêu cầu kiểm tra (phải >= 1)", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity
) {
}
