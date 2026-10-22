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
 */
public record SkuInfo(
        UUID skuId,
        String skuCode,
        String productName,
        String thumbnailUrl,
        BigDecimal price,
        Integer stockQuantity,
        Boolean isActive
) {
}
