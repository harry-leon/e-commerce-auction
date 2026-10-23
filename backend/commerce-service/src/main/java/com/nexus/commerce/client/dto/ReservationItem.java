package com.nexus.commerce.client.dto;

import java.util.UUID;

/**
 * DTO đại diện cho một SKU cần giữ chỗ tồn kho trong đơn hàng.
 *
 * @param skuId    ID định danh của SKU sản phẩm
 * @param quantity Số lượng cần giữ tồn kho (phải > 0)
 */
public record ReservationItem(
        UUID skuId,
        Integer quantity
) {
}
