package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Yêu cầu điều chỉnh tồn kho thực tế (SRS §3.6.1 Inventory Adjustment).
 *
 * Chức năng:
 * - Điều chỉnh tăng hoặc giảm total_qty khi kiểm kê phát hiện sai lệch hoặc hư hại hàng hóa.
 * - Ghi nhận bản ghi ADJUSTMENT vào sổ cái tồn kho.
 *
 * @param skuId       Mã biến thể SKU
 * @param warehouseId Mã kho (tùy chọn, mặc định WH_001)
 * @param deltaQty    Số lượng thay đổi (dương là cộng thêm, âm là giảm bớt, khác 0)
 * @param reason      Lý do điều chỉnh (bắt buộc)
 * @param refId       Mã biên bản kiểm kê (tùy chọn)
 */
public record StockAdjustmentRequest(
        @NotNull(message = "skuId không được để trống")
        UUID skuId,

        UUID warehouseId,

        @NotNull(message = "deltaQty không được để trống")
        Integer deltaQty,

        @NotBlank(message = "Lý do điều chỉnh không được để trống")
        String reason,

        UUID refId
) {
}
