package com.nexus.catalog.repository;

import com.nexus.catalog.model.entity.Sku;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkuRepository extends JpaRepository<Sku, UUID> {

    boolean existsBySkuCode(String skuCode);

    boolean existsBySkuCodeAndIdNot(String skuCode, UUID id);
}
