package com.nexus.commerce.repository;

import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.enums.CartStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA Repository for Cart entities.
 */
@Repository
public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * Finds an active cart for a user (at most 1 active cart per user by uq_user_active_cart index).
     */
    Optional<Cart> findByUserIdAndStatus(UUID userId, CartStatus status);

    /**
     * Checks if a user already has a cart in the specified status.
     */
    boolean existsByUserIdAndStatus(UUID userId, CartStatus status);
}
