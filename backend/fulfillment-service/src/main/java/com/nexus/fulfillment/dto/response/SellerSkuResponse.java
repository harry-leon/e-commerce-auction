package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.SellerSkuStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO danh sách SKU kho của Người bán/Admin tại GET /api/v1/inventory/skus,
 * tổng hợp tồn kho từ tất cả các kho gắn với một bản ghi seller_skus.
 */
public record SellerSkuResponse(
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
        int warehouseCount,
        Instant createdAt,
        Instant updatedAt
) {
}
