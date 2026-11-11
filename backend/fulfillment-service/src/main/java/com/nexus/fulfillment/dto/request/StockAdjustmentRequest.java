package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Yêu cầu điều chỉnh tồn kho thực tế (SRS §3.6.1 Inventory Adjustment).
 *
 * Chức năng:
 * - Hỗ trợ điều chỉnh tương đối (deltaQty) hoặc đặt lại tổng tồn mục tiêu (targetTotalQty) nguyên tử dưới khóa dòng.
 * - Hỗ trợ điều chỉnh hàng hỏng/cách ly (unavailableDeltaQty) mà vẫn giữ bất biến totalQty >= reservedQty + unavailableQty.
 * - Hỗ trợ chống lặp thông qua idempotencyKey hoặc refId; ghi nhận bản ghi ADJUSTMENT vào sổ cái tồn kho.
 *
 * @param skuId               Mã biến thể SKU (tùy chọn nếu có skuCode)
 * @param skuCode             Mã SKU doanh nghiệp (tùy chọn nếu có skuId)
 * @param warehouseId         Mã kho (tùy chọn, mặc định WH_001)
 * @param deltaQty            Số lượng thay đổi tương đối (dương là tăng, âm là giảm)
 * @param targetTotalQty      Tổng tồn mục tiêu muốn đặt lại trực tiếp (tùy chọn thay cho deltaQty)
 * @param unavailableDeltaQty Số lượng thay đổi cho phần hàng lỗi/cách ly (tùy chọn)
 * @param reason              Lý do điều chỉnh (bắt buộc)
 * @param refId               Mã biên bản kiểm kê (tùy chọn)
 * @param idempotencyKey      Khóa chống lặp khi retry request (tùy chọn)
 */
public record StockAdjustmentRequest(
        UUID skuId,

        @Size(max = 100, message = "skuCode tối đa 100 ký tự")
        String skuCode,

        UUID warehouseId,

        Integer deltaQty,

        @Min(value = 0, message = "Tổng tồn mục tiêu không được âm")
        Integer targetTotalQty,

        Integer unavailableDeltaQty,

        @NotBlank(message = "Lý do điều chỉnh không được để trống")
        @Size(max = 500, message = "Lý do điều chỉnh tối đa 500 ký tự")
        String reason,

        UUID refId,

        @Size(max = 128, message = "idempotencyKey tối đa 128 ký tự")
        String idempotencyKey
) {
    /**
     * Constructor tương thích ngược với caller truyền 5 trường cốt lõi.
     */
    public StockAdjustmentRequest(UUID skuId, UUID warehouseId, Integer deltaQty, String reason, UUID refId) {
        this(skuId, null, warehouseId, deltaQty, null, null, reason, refId, null);
    }

    /**
     * Đảm bảo request luôn có skuId hoặc skuCode.
     */
    @AssertTrue(message = "Phải cung cấp skuId hoặc skuCode để điều chỉnh kho")
    public boolean isSkuIdentifierPresent() {
        return skuId != null || (skuCode != null && !skuCode.isBlank());
    }

    /**
     * Đảm bảo request có ít nhất một giá trị điều chỉnh hợp lệ.
     */
    @AssertTrue(message = "Phải cung cấp deltaQty, targetTotalQty hoặc unavailableDeltaQty")
    public boolean isAdjustmentValuePresent() {
        return deltaQty != null || targetTotalQty != null || unavailableDeltaQty != null;
    }
}
