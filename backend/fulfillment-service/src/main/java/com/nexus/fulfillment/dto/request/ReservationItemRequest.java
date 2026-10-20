package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Mục hàng SKU cần giữ trong yêu cầu tạo phiếu giữ hàng (Reservation).
 *
 * Chức năng: Chỉ định mã SKU, mã kho và số lượng cần giữ tạm thời khi đặt hàng.
 *
 * @param skuId       Mã SKU cần giữ
 * @param warehouseId Mã kho hàng (tùy chọn)
 * @param quantity    Số lượng yêu cầu giữ (bắt buộc >= 1)
 */
@Schema(description = "Thông tin SKU và số lượng cần giữ tồn kho")
public record ReservationItemRequest(
        @NotNull(message = "sku_id is required")
        @Schema(description = "Mã SKU", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID skuId,

        @Schema(description = "Mã kho hàng (tùy chọn)", example = "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
        UUID warehouseId,

        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be greater than 0")
        @Schema(description = "Số lượng cần giữ (phải >= 1)", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer quantity
) {
}
