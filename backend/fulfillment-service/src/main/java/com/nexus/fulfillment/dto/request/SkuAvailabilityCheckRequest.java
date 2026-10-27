package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu kiểm tra tồn kho khả dụng hàng loạt cho danh sách SKU (SRS §3.6.1).
 *
 * Chức năng:
 * - Được Commerce Service hoặc frontend Cart gọi để hiển thị số lượng còn hàng trước khi Checkout.
 *
 * @param skuIds      Danh sách mã biến thể SKU cần kiểm tra
 * @param warehouseId Mã kho cụ thể (tùy chọn, nếu null sẽ dùng kho mặc định WH_001)
 */
public record SkuAvailabilityCheckRequest(
        @NotEmpty(message = "Danh sách skuIds không được để trống")
        List<UUID> skuIds,

        UUID warehouseId
) {
}
