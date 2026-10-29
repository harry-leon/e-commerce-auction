package com.nexus.commerce.repository;

import com.nexus.commerce.entity.CartItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/**
 * Spring Data JPA Repository for CartItem entities.
 */
@Repository
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /** Khóa các dòng theo thứ tự ổn định để quantity dùng tính tiền không bị sửa giữa lúc reserve/lưu đơn. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from CartItem i where i.cart.id = :cartId order by i.id")
    List<CartItem> findForCheckout(@Param("cartId") UUID cartId);

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
