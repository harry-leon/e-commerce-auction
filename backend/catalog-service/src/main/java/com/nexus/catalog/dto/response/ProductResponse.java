package com.nexus.catalog.dto.response;

import com.nexus.catalog.enums.ProductStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Khoảng giá (minPrice, maxPrice) được tổng hợp động từ các SKU ACTIVE tại thời điểm phản hồi.
public record ProductResponse(
        UUID id,
        UUID sellerId,
        UUID categoryId,
        String name,
        String description,
        ProductStatus status,
        Boolean auctionEnabled,
        Instant createdAt,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<SkuResponse> skus,
        List<ImageResponse> images) {
}
