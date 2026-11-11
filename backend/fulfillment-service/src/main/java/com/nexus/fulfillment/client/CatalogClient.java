package com.nexus.fulfillment.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng giao tiếp HTTP chỉ đọc sang Catalog Service để xác minh quyền sở hữu SKU của Seller
 * và phân giải mã nghiệp vụ skuCode sang skuId (không truy cập chéo DB catalog).
 */
public interface CatalogClient {

    /**
     * Tra cứu thông tin chủ sở hữu và mã SKU theo skuId.
     */
    Optional<SkuOwnershipInfo> findSkuById(UUID skuId);

    /**
     * Tra cứu thông tin chủ sở hữu và skuId theo mã nghiệp vụ skuCode.
     */
    Optional<SkuOwnershipInfo> findSkuByCode(String skuCode);

    /**
     * Lấy toàn bộ danh sách SKU thuộc quyền quản lý của một sellerId.
     */
    List<SkuOwnershipInfo> findSkusBySeller(UUID sellerId);

    /**
     * Thông tin tối thiểu về SKU từ Catalog để Fulfillment kiểm tra quyền sở hữu và phân giải mã SKU.
     */
    record SkuOwnershipInfo(
            UUID skuId,
            String skuCode,
            String productName,
            UUID sellerId,
            Boolean isActive
    ) {
    }
}
