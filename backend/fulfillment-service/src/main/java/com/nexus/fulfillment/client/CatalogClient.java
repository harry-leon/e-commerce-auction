package com.nexus.fulfillment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cổng giao tiếp HTTP chỉ đọc sang Catalog Service để xác thực SKU tồn tại và quyền sở hữu của Seller.
 * Lưu ý kiến trúc (HG-S2): Catalog không còn quản lý skuCode; mọi mã skuCode được Fulfillment quản lý trong seller_skus.
 */
public interface CatalogClient {

    /**
     * Tra cứu thông tin chủ sở hữu và snapshot giá SKU theo skuId từ Catalog Service.
     */
    Optional<SkuOwnershipInfo> findSkuById(UUID skuId);

    /**
     * Lấy toàn bộ danh sách SKU thuộc quyền quản lý của một sellerId.
     */
    List<SkuOwnershipInfo> findSkusBySeller(UUID sellerId);

    /**
     * Snapshot tối thiểu về SKU từ Catalog để Fulfillment kiểm tra quyền sở hữu và thông tin sản phẩm.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SkuOwnershipInfo(
            UUID skuId,
            UUID productId,
            String productName,
            BigDecimal price,
            BigDecimal originalPrice,
            UUID sellerId,
            Boolean isActive
    ) {
        /** Constructor 5 tham số giữ tương thích cho test kiểm tra quyền sở hữu chỉ quan tâm skuId/sellerId. */
        public SkuOwnershipInfo(UUID skuId, String ignoredSkuCode, String productName, UUID sellerId, Boolean isActive) {
            this(skuId, null, productName, null, null, sellerId, isActive);
        }
    }
}
