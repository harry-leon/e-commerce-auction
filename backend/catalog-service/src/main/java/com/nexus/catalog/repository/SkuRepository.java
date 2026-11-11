package com.nexus.catalog.repository;

import com.nexus.catalog.entity.Sku;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<Sku, UUID> {
    boolean existsBySkuCode(String skuCode);
    boolean existsBySkuCodeAndIdNot(String skuCode, UUID id);

    // Tra cứu biến thể SKU theo mã sku_code không phân biệt hoa thường phục vụ đồng bộ kho/tra cứu liên service.
    Optional<Sku> findBySkuCodeIgnoreCase(String skuCode);
}
