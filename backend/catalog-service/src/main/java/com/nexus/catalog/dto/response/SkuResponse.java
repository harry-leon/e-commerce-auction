package com.nexus.catalog.dto.response;

import com.nexus.catalog.model.enum_.SkuStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record SkuResponse(
        UUID id,
        String skuCode,
        String attributes,
        BigDecimal price,
        BigDecimal originalPrice,
        SkuStatus status) {
}
