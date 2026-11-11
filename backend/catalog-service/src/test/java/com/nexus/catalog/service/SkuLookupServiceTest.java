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
        sku.setOriginalPrice(new BigDecimal("125000"));
        sku.setStatus(SkuStatus.ACTIVE);
        when(skus.findById(id)).thenReturn(Optional.of(sku));
        var snapshot = new SkuLookupService(skus).get(id);
        assertEquals(new BigDecimal("125000.00"), snapshot.price());
        assertEquals(product.getSellerId(), snapshot.sellerId());
        assertNull(snapshot.stockQuantity());
        assertNull(snapshot.thumbnailUrl());
        assertFalse(snapshot.isActive());
    }

    @Test
    void missingSkuReturnsDomainNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> new SkuLookupService(skus).get(UUID.randomUUID()));
    }
}
