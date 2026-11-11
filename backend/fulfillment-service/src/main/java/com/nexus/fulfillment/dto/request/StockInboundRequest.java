package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Yêu cầu nhập kho hàng hóa cho một SKU thuộc sở hữu của Seller (SRS §3.6.1 Inventory Intake / Inbound).
 * Khi nhập kho lần đầu theo skuId, Fulfillment xác thực quyền sở hữu với Catalog và cấp phát mã skuCode (FSKU-XXXXXXXX) trong seller_skus;
 * các lần nhập tiếp theo có thể truyền skuId hoặc skuCode đã cấp phát, hỗ trợ chống lặp theo idempotencyKey/refId.
 *
 * @param skuId          Mã biến thể SKU từ Catalog Service (bắt buộc ở lần nhập đầu tiên)
 * @param skuCode        Mã SKU nội bộ do Fulfillment cấp phát (tùy chọn thay cho skuId ở lần nhập lặp lại)
 * @param warehouseId    Mã kho nhập (tùy chọn, mặc định WH_001)
 * @param quantity       Số lượng nhập kho (tối thiểu là 1)
 * @param refId          Mã chứng từ nhập kho hoặc lô hàng (tùy chọn, dùng chống lặp theo chứng từ)
 * @param unitCost       Đơn giá nhập tham khảo tại lần nhập kho (tùy chọn)
 * @param reason         Ghi chú chứng từ nhập kho (tùy chọn)
 * @param idempotencyKey Khóa chống lặp cho thao tác nhập kho (tùy chọn, ưu tiên header Idempotency-Key)
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

        @DecimalMin(value = "0", inclusive = true, message = "Đơn giá nhập không được âm")
        BigDecimal unitCost,

        @Size(max = 500, message = "Lý do nhập kho tối đa 500 ký tự")
        String reason,

        @Size(max = 128, message = "idempotencyKey tối đa 128 ký tự")
        String idempotencyKey
) {
    /** Constructor 4 tham số giữ tương thích ngược cho caller chỉ truyền trường cốt lõi. */
    public StockInboundRequest(UUID skuId, UUID warehouseId, Integer quantity, UUID refId) {
        this(skuId, null, warehouseId, quantity, refId, null, null, null);
    }

    /** Constructor 5 tham số giữ tương thích cho caller truyền kèm đơn giá nhập unitCost. */
    public StockInboundRequest(UUID skuId, UUID warehouseId, Integer quantity, UUID refId, BigDecimal unitCost) {
        this(skuId, null, warehouseId, quantity, refId, unitCost, null, null);
    }

    /** Constructor 7 tham số giữ tương thích cho caller truyền skuCode, reason và idempotencyKey. */
    public StockInboundRequest(
            UUID skuId,
            String skuCode,
            UUID warehouseId,
            Integer quantity,
            UUID refId,
            String reason,
            String idempotencyKey
    ) {
        this(skuId, skuCode, warehouseId, quantity, refId, null, reason, idempotencyKey);
    }

    /** Đảm bảo request luôn có ít nhất một định danh SKU hợp lệ (skuId hoặc skuCode). */
    @AssertTrue(message = "Phải cung cấp skuId hoặc skuCode để nhập kho")
    public boolean isSkuIdentifierPresent() {
        return skuId != null || (skuCode != null && !skuCode.isBlank());
    }
}
