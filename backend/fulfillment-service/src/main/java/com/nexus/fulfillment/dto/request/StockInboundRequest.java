package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Yêu cầu nhập kho hàng hóa cho một SKU (SRS §3.6.1 Inventory Intake / Inbound).
 *
 * Chức năng:
 * - Tăng số lượng total_qty và tự động phản ánh vào available_qty của SKU.
 * - Ghi nhận bản ghi INBOUND vào Sổ cái tồn kho bất biến (inventory_ledger).
 *
 * @param skuId       Mã biến thể SKU
 * @param warehouseId Mã kho nhập (tùy chọn, mặc định WH_001)
 * @param quantity    Số lượng nhập kho (tối thiểu là 1)
 * @param refId       Mã chứng từ nhập kho hoặc lô hàng (tùy chọn)
 */
public record StockInboundRequest(
        @NotNull(message = "skuId không được để trống")
        UUID skuId,

        UUID warehouseId,

        @NotNull(message = "Số lượng nhập không được để trống")
        @Min(value = 1, message = "Số lượng nhập kho tối thiểu phải là 1")
        Integer quantity,

        UUID refId
) {
}
