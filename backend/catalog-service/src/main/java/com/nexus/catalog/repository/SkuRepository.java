package com.nexus.catalog.repository;

import com.nexus.catalog.entity.Sku;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository quản lý thực thể SKU trong phạm vi Catalog Service (không còn truy vấn sku_code). */
public interface SkuRepository extends JpaRepository<Sku, UUID> {
    List<Sku> findByProductId(UUID productId);
}
