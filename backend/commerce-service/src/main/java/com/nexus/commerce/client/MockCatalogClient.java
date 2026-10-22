package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.SkuInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default fallback / stub implementation of CatalogClient.
 * Provides resilient stubbed data during early integration testing until Feign client is activated.
 */
@Component
@ConditionalOnMissingBean(name = "catalogFeignClient")
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
