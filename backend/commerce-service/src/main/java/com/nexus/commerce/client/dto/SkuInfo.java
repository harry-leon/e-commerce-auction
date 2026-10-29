package com.nexus.commerce.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Data Transfer Object representing SKU variant details fetched from Catalog Service.
 *
 * @param skuId         Unique identifier of the SKU variant
 * @param skuCode       SKU code identifier (e.g. NK-AF1-WHT-42)
 * @param productName   Name of the product
 * @param thumbnailUrl  URL link to the primary thumbnail image
 * @param price         Current unit listing price
 * @param stockQuantity Available inventory quantity in stock
 * @param isActive      Whether the SKU variant is currently active for purchase
 * @param sellerId      Chủ shop từ Catalog; Checkout không chấp nhận giá trị do client tự khai báo
 */
public record SkuInfo(
        UUID skuId,
        String skuCode,
        String productName,
        String thumbnailUrl,
        BigDecimal price,
        Integer stockQuantity,
        Boolean isActive,
        UUID sellerId
) {
    /** Giữ tương thích caller Cart cũ; Checkout yêu cầu sellerId thật và từ chối snapshot thiếu người bán. */
    public SkuInfo(UUID skuId, String skuCode, String productName, String thumbnailUrl,
                   BigDecimal price, Integer stockQuantity, Boolean isActive) {
        this(skuId, skuCode, productName, thumbnailUrl, price, stockQuantity, isActive, null);
    }
}
