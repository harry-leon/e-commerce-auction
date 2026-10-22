package com.nexus.commerce.repository;

import com.nexus.commerce.entity.CartItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for CartItem entities.
 */
@Repository
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /**
     * Find a cart item by cart ID and SKU ID (enforced unique by uq_cart_sku constraint).
     */
    Optional<CartItem> findByCartIdAndSkuId(UUID cartId, UUID skuId);

    /**
     * Find all items belonging to a given cart ID.
     */
    List<CartItem> findByCartId(UUID cartId);

    /**
     * Delete all items belonging to a cart ID.
     */
    void deleteByCartId(UUID cartId);
}
