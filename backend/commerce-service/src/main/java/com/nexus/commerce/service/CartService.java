package com.nexus.commerce.service;

import com.nexus.commerce.dto.request.AddToCartRequest;
import com.nexus.commerce.dto.request.MergeCartRequest;
import com.nexus.commerce.dto.request.UpdateCartItemRequest;
import com.nexus.commerce.dto.response.CartResponse;
import java.util.UUID;

/**
 * Service interface defining operations for managing user shopping carts.
 */
public interface CartService {

    /**
     * Retrieves the current active cart for the specified user.
     * Automatically creates a new cart if one does not exist or if the current one has expired.
     *
     * @param userId The ID of the authenticated user
     * @return CartResponse containing active cart details and calculated pricing
     */
    CartResponse getActiveCart(UUID userId);

    /**
     * Adds a product SKU to the user's active cart.
     * Increments the quantity if the SKU is already in the cart.
     *
     * @param userId  The ID of the user
     * @param request The AddToCartRequest containing skuId and quantity
     * @return CartResponse with the updated cart details
     */
    CartResponse addItem(UUID userId, AddToCartRequest request);

    /**
     * Updates the quantity of a specific cart item line.
     *
     * @param userId  The ID of the user
     * @param itemId  The ID of the cart item line to update
     * @param request The UpdateCartItemRequest containing the new quantity
     * @return CartResponse with the updated cart details
     */
    CartResponse updateItemQuantity(UUID userId, UUID itemId, UpdateCartItemRequest request);

    /**
     * Removes a specific item line from the user's active cart.
     *
     * @param userId The ID of the user
     * @param itemId The ID of the cart item line to remove
     * @return CartResponse with the updated cart details
     */
    CartResponse removeItem(UUID userId, UUID itemId);

    /**
     * Clears all item lines from the user's active cart.
     *
     * @param userId The ID of the user
     */
    void clearCart(UUID userId);

    /**
     * Merges items from a guest cart into the authenticated user's active cart.
     *
     * @param userId  The ID of the user
     * @param request The MergeCartRequest containing guest items
     * @return CartResponse with the updated merged cart details
     */
    CartResponse mergeCart(UUID userId, MergeCartRequest request);
}
