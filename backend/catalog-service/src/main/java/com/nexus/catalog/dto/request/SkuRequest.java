package com.nexus.catalog.dto.request;

import com.nexus.catalog.enums.SkuStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SkuRequest(
        @NotBlank @Size(max = 100) String skuCode,
        String attributes,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal price,
        @DecimalMin(value = "0", inclusive = true) BigDecimal originalPrice,
        SkuStatus status) {
}
