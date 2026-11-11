package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Yêu cầu nhập kho hàng hóa cho một Catalog SKU (SRS §3.6.1 Inventory Intake / Inbound).
 * Khi nhập kho lần đầu, Fulfillment tự xác thực quyền sở hữu với Catalog và cấp phát mã skuCode trong seller_skus.
 *
 * @param skuId       Mã biến thể SKU từ Catalog Service
 * @param warehouseId Mã kho nhập (tùy chọn, mặc định WH_001)
 * @param quantity    Số lượng nhập kho (tối thiểu là 1)
 * @param refId       Mã chứng từ nhập kho hoặc lô hàng (tùy chọn)
 * @param unitCost    Đơn giá nhập tham khảo tại lần nhập kho (tùy chọn)
 */
public record StockInboundRequest(
        @NotNull(message = "skuId không được để trống")
        UUID skuId,

        UUID warehouseId,

        @NotNull(message = "Số lượng nhập không được để trống")
        @Min(value = 1, message = "Số lượng nhập kho tối thiểu phải là 1")
        Integer quantity,

        UUID refId,

        @DecimalMin(value = "0", inclusive = true, message = "Đơn giá nhập không được âm")
        BigDecimal unitCost
) {
    /** Constructor giữ tương thích cho caller chỉ truyền 4 tham số cơ bản. */
    public StockInboundRequest(UUID skuId, UUID warehouseId, Integer quantity, UUID refId) {
        this(skuId, warehouseId, quantity, refId, null);
    }
}
