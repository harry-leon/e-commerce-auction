package com.nexus.commerce.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request payload for merging a guest shopping cart upon user sign-in.
 *
 * @param items List of SKU items from the guest session to merge into user's active cart
 */
public record MergeCartRequest(
        @NotEmpty(message = "Items list must not be empty")
        List<@Valid AddToCartRequest> items
) {
}
