package com.nexus.catalog.repository;

import com.nexus.catalog.entity.ProductReview;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {
    Page<ProductReview> findByProductIdAndIsHiddenFalse(UUID productId, Pageable pageable);
    Page<ProductReview> findByUserId(UUID userId, Pageable pageable);
    boolean existsByProductIdAndOrderIdAndUserId(UUID productId, UUID orderId, UUID userId);
}
