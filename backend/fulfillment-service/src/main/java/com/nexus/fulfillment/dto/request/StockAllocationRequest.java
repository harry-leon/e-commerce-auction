package com.nexus.fulfillment.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * DTO yêu cầu phân bổ tồn kho đa kho cho đơn hàng (SRS §3.6.1, KH-S3-2).
 *
 * Chức năng:
 * - Chứa danh sách mặt hàng SKU và số lượng cần đặt mua trong đơn hàng.
 * - Đầu vào cho thuật toán phân bổ kho ưu tiên DEFAULT_WAREHOUSE_ID (WH_001) và tối đa MAX_WAREHOUSES_PER_ORDER = 2.
 */
public record StockAllocationRequest(
        @NotNull(message = "Mã đơn hàng orderId không được để trống")
        UUID orderId,

        @NotEmpty(message = "Danh sách mặt hàng phân bổ không được để trống")
        @Valid
        List<AllocationItemRequest> items
) {
    public record AllocationItemRequest(
            @NotNull(message = "Mã skuId không được để trống")
            UUID skuId,

            @NotNull(message = "Số lượng quantity không được để trống")
            @Min(value = 1, message = "Số lượng yêu cầu tối thiểu phải từ 1")
            Integer quantity
    ) {
    }
}
