package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response payload representing an individual item line in the shopping cart.
 *
 * @param id             Identifier of the cart item line
 * @param skuId          Identifier of the product SKU variant
 * @param skuCode        SKU code string (e.g. NK-AF1-WHT-42)
 * @param productName    Display name of the product
 * @param thumbnailUrl   URL to the thumbnail image
 * @param unitPrice      Current unit price of the item
 * @param quantity       Quantity of items requested in the cart
 * @param stockAvailable Quantity currently available in stock
 * @param isAvailable    Whether the item is currently available for purchase
 * @param subtotal       Subtotal for this line item (unitPrice * quantity)
 */
public record CartItemResponse(
        UUID id,
        UUID skuId,
        String skuCode,
        String productName,
        String thumbnailUrl,
        BigDecimal unitPrice,
        Integer quantity,
        Integer stockAvailable,
        Boolean isAvailable,
        BigDecimal subtotal
) {
}
