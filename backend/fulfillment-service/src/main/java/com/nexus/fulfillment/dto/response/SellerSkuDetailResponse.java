package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.SellerSkuStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO chi tiết SKU kho tại GET /api/v1/inventory/skus/{id}, kèm danh sách tồn kho theo từng kho hàng.
 */
public record SellerSkuDetailResponse(
        UUID id,
        UUID sellerSkuId,
        UUID sellerId,
        UUID skuId,
        String skuCode,
        SellerSkuStatus status,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty,
        List<InventoryStockResponse> warehouses,
        Instant createdAt,
        Instant updatedAt
) {
}
