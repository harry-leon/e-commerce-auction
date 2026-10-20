package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Thông tin chi tiết một mục hàng được giữ trong phiếu giữ hàng (Reservation).
 *
 * Chức năng: Đại diện cho 1 dòng dữ liệu trong bảng inventory_reservations liên kết với tồn kho của SKU tại 1 kho.
 *
 * @param id          Mã định danh bản ghi giữ hàng (dòng reservation)
 * @param skuId       Mã SKU được giữ
 * @param warehouseId Mã kho hàng thực hiện giữ tồn
 * @param quantity    Số lượng hàng được giữ
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Chi tiết một dòng hàng trong phiếu giữ tồn kho")
public record ReservationItemResponse(
        @Schema(description = "Mã bản ghi giữ hàng", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID id,

        @Schema(description = "Mã SKU", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
        UUID skuId,

        @Schema(description = "Mã kho hàng", example = "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
        UUID warehouseId,

        @Schema(description = "Số lượng đã giữ", example = "2")
        Integer quantity
) {
}
