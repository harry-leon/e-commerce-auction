package com.nexus.fulfillment.repository;

import com.nexus.fulfillment.entity.SellerSku;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Repository quản lý bảng seller_skus (kèm JpaSpecificationExecutor lọc động an toàn trên PostgreSQL) và sinh sequence skuCode. */
public interface SellerSkuRepository extends JpaRepository<SellerSku, UUID>, JpaSpecificationExecutor<SellerSku> {

    Optional<SellerSku> findBySkuId(UUID skuId);

    List<SellerSku> findBySkuIdIn(Collection<UUID> skuIds);

    Optional<SellerSku> findByIdOrSkuId(UUID id, UUID skuId);

    @Query(value = "SELECT nextval('fulfillment_sku_code_seq')", nativeQuery = true)
    long nextSkuCodeSequenceValue();
}
