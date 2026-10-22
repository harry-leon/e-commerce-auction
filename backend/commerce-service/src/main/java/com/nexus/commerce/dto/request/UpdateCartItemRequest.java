package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request payload for updating the item quantity in a shopping cart.
 *
 * @param quantity New quantity of units for the cart item (must be at least 1)
 */
public record UpdateCartItemRequest(
        @NotNull(message = "quantity must not be null")
        @Min(value = 1, message = "quantity must be greater than 0")
        Integer quantity
) {
}
