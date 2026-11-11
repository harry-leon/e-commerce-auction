package com.nexus.fulfillment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Hợp đồng giao tiếp HTTP sang Catalog Service để xác thực SKU trước khi Fulfillment cấp mã skuCode và nhập kho.
 * Fulfillment tuyệt đối không truy vấn hay ghi trực tiếp vào schema database của Catalog.
 */
public interface CatalogSkuClient {

    /**
     * Tra cứu thông tin SKU và chủ sở hữu (sellerId) từ Catalog theo skuId.
     * Ném ResourceNotFoundException (404) nếu SKU không tồn tại, hoặc ApiException (502) nếu Catalog không phản hồi.
     */
    CatalogSkuSnapshot getSku(UUID skuId);

    /** Snapshot tối thiểu nhận từ Catalog API GET /api/v1/skus/{id}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogSkuSnapshot(
            UUID skuId,
            UUID productId,
            String productName,
            BigDecimal price,
            BigDecimal originalPrice,
            Boolean isActive,
            UUID sellerId) {
    }
}
