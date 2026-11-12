package com.nexus.fulfillment.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * DTO phản hồi kết quả phân bổ tồn kho đa kho cho đơn hàng (SRS §3.6.1, KH-S3-2).
 *
 * Chức năng:
 * - Trả về danh sách kho được phân bổ và số lượng chi tiết từng SKU cho mỗi kho.
 * - Đảm bảo ràng buộc số lượng kho phân bổ không vượt quá MAX_WAREHOUSES_PER_ORDER = 2.
 */
public record StockAllocationResponse(
        UUID orderId,
        int totalWarehouses,
        boolean singleWarehouseFulfilled,
        List<WarehouseAllocationPlan> allocations
) {
    public record WarehouseAllocationPlan(
            UUID warehouseId,
            String warehouseCode,
            String warehouseName,
            List<AllocatedSkuItem> items
    ) {
    }

    public record AllocatedSkuItem(
            UUID skuId,
            int allocatedQuantity
    ) {
    }
}
