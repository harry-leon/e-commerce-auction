package com.nexus.catalog.repository;

import com.nexus.catalog.model.entity.ProductImage;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {
}