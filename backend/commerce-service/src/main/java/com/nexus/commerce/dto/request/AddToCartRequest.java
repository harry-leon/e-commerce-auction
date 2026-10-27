package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request payload for adding a product SKU variant to the shopping cart.
 *
 * @param skuId    Unique identifier of the product SKU variant to add
 * @param quantity Number of units to add (must be at least 1)
 */
public record AddToCartRequest(
        @NotNull(message = "skuId must not be null")
        UUID skuId,

        @NotNull(message = "quantity must not be null")
        @Min(value = 1, message = "quantity must be greater than 0")
        Integer quantity
) {
}
