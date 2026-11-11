package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Yêu cầu nhập kho hàng hóa cho một SKU (SRS §3.6.1 Inventory Intake / Inbound).
 *
 * Chức năng:
 * - Tăng số lượng total_qty và tự động phản ánh vào available_qty của SKU.
 * - Hỗ trợ định danh theo skuId (UUID) hoặc skuCode doanh nghiệp; hỗ trợ chống lặp qua idempotencyKey/refId.
 * - Ghi nhận bản ghi INBOUND vào Sổ cái tồn kho bất biến (inventory_ledger).
 *
 * @param skuId          Mã biến thể SKU (tùy chọn nếu có skuCode)
 * @param skuCode        Mã SKU doanh nghiệp (tùy chọn nếu có skuId, sẽ tra cứu qua Catalog Service)
 * @param warehouseId    Mã kho nhập (tùy chọn, mặc định WH_001)
 * @param quantity       Số lượng nhập kho (tối thiểu là 1)
 * @param refId          Mã chứng từ nhập kho hoặc lô hàng (tùy chọn, dùng chống lặp theo chứng từ)
 * @param reason         Ghi chú chứng từ nhập kho (tùy chọn)
 * @param idempotencyKey Khóa chống lặp cho thao tác nhập kho (tùy chọn, có thể truyền qua header Idempotency-Key)
 */
public record StockInboundRequest(
        UUID skuId,

        @Size(max = 100, message = "skuCode tối đa 100 ký tự")
        String skuCode,

        UUID warehouseId,

        @NotNull(message = "Số lượng nhập không được để trống")
        @Min(value = 1, message = "Số lượng nhập kho tối thiểu phải là 1")
        Integer quantity,

        UUID refId,

        @Size(max = 500, message = "Lý do nhập kho tối đa 500 ký tự")
        String reason,

        @Size(max = 128, message = "idempotencyKey tối đa 128 ký tự")
        String idempotencyKey
) {
    /**
     * Constructor tương thích ngược với caller chỉ truyền 4 trường cốt lõi.
     */
    public StockInboundRequest(UUID skuId, UUID warehouseId, Integer quantity, UUID refId) {
        this(skuId, null, warehouseId, quantity, refId, null, null);
    }

    /**
     * Đảm bảo request luôn có ít nhất một định danh SKU hợp lệ (skuId hoặc skuCode).
     */
    @AssertTrue(message = "Phải cung cấp skuId hoặc skuCode để nhập kho")
    public boolean isSkuIdentifierPresent() {
        return skuId != null || (skuCode != null && !skuCode.isBlank());
    }
}
