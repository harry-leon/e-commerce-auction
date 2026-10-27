package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.SkuInfo;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client port for fetching product and SKU details from the Catalog microservice.
 */
public interface CatalogClient {

    /**
     * Retrieves details for a specific SKU variant.
     *
     * @param skuId SKU identifier
     * @return SkuInfo containing price, status, and stock quantity
     */
    SkuInfo getSkuInfo(UUID skuId);

    /**
     * Bulk retrieves details for a collection of SKU identifiers.
     *
     * @param skuIds List of SKU identifiers
     * @return Map of skuId -> SkuInfo
     */
    Map<UUID, SkuInfo> getSkuDetails(List<UUID> skuIds);
}
