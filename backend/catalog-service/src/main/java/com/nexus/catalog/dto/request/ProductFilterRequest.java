package com.nexus.catalog.dto.request;

import com.nexus.catalog.enums.ProductStatus;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductFilterRequest (
    String keyword,       // Search by product name / description / category name
    UUID categoryId,      // Filter by category
    @Min(value = 0, message = "minPrice must be greater than or equal to 0")
    BigDecimal minPrice,  // Filter by price range
    @Min(value = 0, message = "maxPrice must be greater than or equal to 0")
    BigDecimal maxPrice,
    ProductStatus status // Public search should default to ACTIVE
){}
