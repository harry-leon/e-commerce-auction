package com.nexus.catalog.dto.request;

import com.nexus.catalog.enums.SkuStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thông tin biến thể sản phẩm khi tạo hoặc cập nhật trong Catalog.
 * id tùy chọn dùng để giữ nguyên skuId hiện có khi cập nhật sản phẩm;
 * skuCode không thuộc Catalog mà được cấp phát tự động tại Fulfillment khi nhập kho.
 */
public record SkuRequest(
        UUID id,
        String attributes,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal originalPrice,
        SkuStatus status) {
}
