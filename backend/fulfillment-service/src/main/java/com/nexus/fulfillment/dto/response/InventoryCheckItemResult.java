package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Kết quả kiểm tra tồn kho cho từng SKU cụ thể.
 *
 * Chức năng: Trả về trạng thái khả dụng và số lượng tồn hiện có cho một SKU tại kho chỉ định (hoặc kho mặc định).
 *
 * @param skuId             Mã định danh SKU cần kiểm tra
 * @param warehouseId       Mã định danh kho hàng chứa tồn kho (nếu có)
 * @param requestedQuantity Số lượng yêu cầu kiểm tra
 * @param availableQuantity Số lượng khả dụng hiện có (total - reserved - unavailable)
 * @param available         true nếu availableQuantity >= requestedQuantity, false nếu không đủ
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Kết quả kiểm tra tồn kho của từng SKU")
public record InventoryCheckItemResult(
        @Schema(description = "Mã SKU", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
        UUID skuId,

        @Schema(description = "Mã kho hàng", example = "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
        UUID warehouseId,

        @Schema(description = "Số lượng yêu cầu kiểm tra", example = "2")
        Integer requestedQuantity,

        @Schema(description = "Số lượng tồn kho khả dụng hiện tại", example = "10")
        Integer availableQuantity,

        @Schema(description = "Trạng thái đáp ứng đủ tồn kho hay không", example = "true")
        boolean available
) {
}
