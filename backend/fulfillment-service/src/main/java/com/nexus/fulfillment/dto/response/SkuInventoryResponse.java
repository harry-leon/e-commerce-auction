package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Thông tin chi tiết số lượng tồn kho của một SKU.
 *
 * Chức năng: Cung cấp số lượng tổng, số lượng đang giữ, số lượng hỏng/không khả dụng và số lượng khả dụng bán được
 * theo đúng công thức nghiệp vụ BR-CFG-32 & BR-INV-03 (available = total - reserved - unavailable).
 *
 * @param skuId               Mã SKU
 * @param warehouseId         Mã kho hàng (nếu truy vấn theo kho cụ thể)
 * @param totalQuantity       Tổng số lượng vật lý trong kho (total_qty)
 * @param reservedQuantity    Số lượng đang được giữ cho các đơn hàng/đấu giá chưa hoàn tất (reserved_qty)
 * @param unavailableQuantity Số lượng hàng lỗi, hỏng, bảo hành không khả dụng bán (unavailable_qty)
 * @param availableQuantity   Số lượng thực tế có thể bán ngay (total_qty - reserved_qty - unavailable_qty)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Thông tin số lượng tồn kho của SKU")
public record SkuInventoryResponse(
        @Schema(description = "Mã SKU", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
        UUID skuId,

        @Schema(description = "Mã kho hàng", example = "f8c3de3d-1fea-4d7c-a8b0-29f63c4c3454")
        UUID warehouseId,

        @Schema(description = "Tổng số lượng tồn vật lý", example = "100")
        Integer totalQuantity,

        @Schema(description = "Số lượng đang được giữ tạm thời", example = "15")
        Integer reservedQuantity,

        @Schema(description = "Số lượng hàng hỏng hoặc không khả dụng", example = "5")
        Integer unavailableQuantity,

        @Schema(description = "Số lượng thực tế khả dụng để bán", example = "80")
        Integer availableQuantity
) {
}
