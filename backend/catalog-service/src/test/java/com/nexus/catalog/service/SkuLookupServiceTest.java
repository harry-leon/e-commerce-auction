package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.SkuRepository;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Catalog snapshot phải dùng giá DB thật và không giả lập tồn kho thuộc Fulfillment. */
@ExtendWith(MockitoExtension.class)
class SkuLookupServiceTest {
    @Mock private SkuRepository skus;

    @Test
    // Snapshot phải mang seller từ Product thật để Commerce kiểm tra chủ shop, không dùng ID client.
    void returnsPriceAndMarksInactiveProductUnavailable() {
        UUID id = UUID.randomUUID();
        var product = new Product();
        product.setName("Product");
        product.setSellerId(UUID.randomUUID());
        product.setStatus(ProductStatus.DRAFT);
        var sku = new Sku();
        sku.setId(id);
        sku.setProduct(product);
        sku.setSkuCode("SKU");
        sku.setPrice(new BigDecimal("125000"));
        sku.setStatus(SkuStatus.ACTIVE);
        when(skus.findById(id)).thenReturn(Optional.of(sku));
        var snapshot = new SkuLookupService(skus).get(id);
        assertEquals(new BigDecimal("125000"), snapshot.price());
        assertEquals(product.getSellerId(), snapshot.sellerId());
        assertNull(snapshot.stockQuantity());
        assertNull(snapshot.thumbnailUrl());
        assertFalse(snapshot.isActive());
    }

    @Test
    void missingSkuReturnsDomainNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> new SkuLookupService(skus).get(UUID.randomUUID()));
    }

    /** Kiểm tra tra cứu SKU theo skuCode trả đúng skuId/sellerId và từ chối mã rỗng hoặc không tồn tại. */
    @Test
    void resolvesSnapshotBySkuCodeAndRejectsBlankOrMissing() {
        UUID id = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        var product = new Product();
        product.setName("Bàn phím cơ");
        product.setSellerId(sellerId);
        product.setStatus(ProductStatus.ACTIVE);
        var sku = new Sku();
        sku.setId(id);
        sku.setProduct(product);
        sku.setSkuCode("NX-KB-01");
        sku.setPrice(new BigDecimal("890000"));
        sku.setStatus(SkuStatus.ACTIVE);
        when(skus.findBySkuCodeIgnoreCase("NX-KB-01")).thenReturn(Optional.of(sku));

        var snapshot = new SkuLookupService(skus).getByCode("  NX-KB-01  ");
        assertEquals(id, snapshot.skuId());
        assertEquals(sellerId, snapshot.sellerId());
        assertEquals("NX-KB-01", snapshot.skuCode());

        assertThrows(ResourceNotFoundException.class, () -> new SkuLookupService(skus).getByCode(" "));
        assertThrows(ResourceNotFoundException.class, () -> new SkuLookupService(skus).getByCode("UNKNOWN"));
    }
}
