package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.SkuInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Chỉ cung cấp SKU demo khi bật profile catalog-mock; mặc định không dùng giá/tồn giả.
 */
@Component
@Profile("catalog-mock")
public class MockCatalogClient implements CatalogClient {

    @Override
    public SkuInfo getSkuInfo(UUID skuId) {
        return new SkuInfo(
                skuId,
                "SKU-" + skuId.toString().substring(0, 8).toUpperCase(),
                "Product " + skuId.toString().substring(0, 8),
                "https://cdn.nexus.com/images/default.jpg",
                new BigDecimal("299000.00"),
                100,
                true
        );
    }

    @Override
    public Map<UUID, SkuInfo> getSkuDetails(List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return Map.of();
        }
        return skuIds.stream()
                .distinct()
                .map(this::getSkuInfo)
                .collect(Collectors.toMap(SkuInfo::skuId, Function.identity()));
    }
}
