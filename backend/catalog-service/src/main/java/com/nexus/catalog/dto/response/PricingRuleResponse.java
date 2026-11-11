package com.nexus.catalog.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dữ liệu quy tắc giá động trả về cho Admin hoặc client xem cấu hình đang áp dụng. */
public record PricingRuleResponse(
        UUID id,
        String name,
        UUID categoryId,
        String categoryName,
        BigDecimal marginRate,
        BigDecimal seasonFactor,
        BigDecimal marketAdjustmentRate,
        Integer priority,
        Instant startDate,
        Instant endDate,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt) {
}
