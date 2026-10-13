package com.nexus.catalog.repository;

import com.nexus.catalog.model.entity.Product;
import com.nexus.catalog.model.entity.ProductImage;
import com.nexus.catalog.model.enum_.ProductStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findBySellerId(UUID sellerId, Pageable pageable);

    Optional<ProductImage> findImagesById(UUID imageId);
}
