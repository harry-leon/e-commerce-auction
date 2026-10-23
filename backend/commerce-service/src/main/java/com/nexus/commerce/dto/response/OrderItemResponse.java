package com.nexus.commerce.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO phản hồi thông tin chi tiết một dòng sản phẩm trong đơn hàng.
 *
 * @param id          Mã định danh dòng sản phẩm đơn hàng
 * @param skuId       Mã định danh SKU
 * @param skuCode     Mã SKU code (ví dụ: NK-AF1-WHT-42)
 * @param productName Tên sản phẩm tại thời điểm đặt hàng
 * @param unitPrice   Đơn giá tại thời điểm đặt hàng
 * @param quantity    Số lượng mua
 * @param subtotal    Thành tiền = unitPrice * quantity
 */
@Schema(description = "Thông tin chi tiết dòng sản phẩm trong đơn hàng")
public record OrderItemResponse(
        @Schema(description = "ID của dòng sản phẩm đơn hàng")
        UUID id,

        @Schema(description = "ID của SKU")
        UUID skuId,

        @Schema(description = "Mã SKU", example = "NK-AF1-WHT-42")
        String skuCode,

        @Schema(description = "Tên sản phẩm", example = "Nike Air Force 1 '07")
        String productName,

        @Schema(description = "Đơn giá snapshot", example = "2900000.00")
        BigDecimal unitPrice,

        @Schema(description = "Số lượng mua", example = "2")
        Integer quantity,

        @Schema(description = "Tổng tiền dòng sản phẩm", example = "5800000.00")
        BigDecimal subtotal
) {
}
