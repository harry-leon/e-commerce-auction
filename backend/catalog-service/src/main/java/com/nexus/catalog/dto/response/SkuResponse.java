package com.nexus.catalog.dto.response;

import com.nexus.catalog.enums.SkuStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO phản hồi thông tin SKU thuộc Catalog (không chứa skuCode kho của Fulfillment).
 */
public record SkuResponse(
        UUID id,
        String attributes,
        BigDecimal price,
        BigDecimal originalPrice,
        SkuStatus status) {
}
