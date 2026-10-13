package com.nexus.catalog.dto.response;

import com.nexus.catalog.model.enum_.ProductStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID sellerId,
        UUID categoryId,
        String name,
        String description,
        ProductStatus status,
        Boolean auctionEnabled,
        Instant createdAt,
        List<SkuResponse> skus,
        List<ImageResponse> images) {
}
