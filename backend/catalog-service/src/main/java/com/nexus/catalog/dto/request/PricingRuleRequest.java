package com.nexus.catalog.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Dữ liệu tạo hoặc cập nhật quy tắc tính giá động theo biên lợi nhuận, mùa vụ và thị trường. */
public record PricingRuleRequest(
        @NotBlank @Size(max = 150) String name,
        UUID categoryId,
        @NotNull @DecimalMin("0") @DecimalMax("10") BigDecimal marginRate,
        @NotNull @DecimalMin("-1") @DecimalMax("10") BigDecimal seasonFactor,
        @NotNull @DecimalMin("-1") @DecimalMax("10") BigDecimal marketAdjustmentRate,
        @NotNull Integer priority,
        Instant startDate,
        Instant endDate,
        Boolean isActive) {
}
