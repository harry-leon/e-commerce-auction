package com.nexus.catalog.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/** Kết quả xem trước giá bán động tính từ giá nhập tối thiểu và quy tắc đang hiệu lực. */
public record PricePreviewResponse(
        BigDecimal originalPrice,
        BigDecimal sellingPrice,
        UUID appliedRuleId,
        String appliedRuleName,
        BigDecimal marginRate,
        BigDecimal seasonFactor,
        BigDecimal marketAdjustmentRate,
        BigDecimal effectiveRate) {
}
