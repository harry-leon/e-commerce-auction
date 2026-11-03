package com.nexus.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Thông tin một dòng mặt hàng trong hóa đơn / email xác nhận đơn hàng (SRS §3.2.3).
 *
 * Chức năng:
 * - Chứa tên sản phẩm, mã SKU, số lượng đặt mua và đơn giá để hiển thị trên email hóa đơn.
 */
public record OrderItemEmailDto(
        @NotBlank(message = "Tên sản phẩm không được để trống")
        String productName,

        @NotBlank(message = "Mã SKU không được để trống")
        String skuCode,

        @NotNull(message = "Số lượng không được để trống")
        @Positive(message = "Số lượng phải lớn hơn 0")
        Integer quantity,

        @NotNull(message = "Đơn giá không được để trống")
        BigDecimal unitPrice
) {
    public BigDecimal calculateSubtotal() {
        if (unitPrice == null || quantity == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
