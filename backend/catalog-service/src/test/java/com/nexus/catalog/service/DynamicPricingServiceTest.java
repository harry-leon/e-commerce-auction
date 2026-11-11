package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.response.PricePreviewResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.PricingRule;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.PricingRuleRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Kiểm tra công thức tính giá bán động từ Sku.originalPrice, ưu tiên quy tắc theo danh mục và tổng hợp khoảng giá Product. */
class DynamicPricingServiceTest {

    @Test
    void calculatesSellingPriceUsingHighestPriorityCategoryRuleWithAncestors() {
        PricingRuleRepository repo = mock(PricingRuleRepository.class);
        DynamicPricingService service = new DynamicPricingService(repo);

        Category parentCat = new Category();
        parentCat.setId(UUID.randomUUID());
        Category childCat = new Category();
        childCat.setId(UUID.randomUUID());
        childCat.setParent(parentCat);

        PricingRule globalRule = rule("Global +10%", null, "0.10", "0.00", "0.00", 1);
        PricingRule parentRule = rule("Parent +20% Season +5%", parentCat, "0.20", "0.05", "-0.05", 5);
        when(repo.findActiveRulesAt(any(Instant.class))).thenReturn(List.of(parentRule, globalRule));

        PricePreviewResponse preview = service.previewPrice(new BigDecimal("100000"), childCat);
        assertEquals(new BigDecimal("120000.00"), preview.sellingPrice());
        assertEquals("Parent +20% Season +5%", preview.appliedRuleName());
    }

    @Test
    void clampsNegativeCombinedRateSoSellingPriceNeverFallsBelowOriginalPrice() {
        PricingRuleRepository repo = mock(PricingRuleRepository.class);
        DynamicPricingService service = new DynamicPricingService(repo);

        PricingRule discountRule = rule("Clearance", null, "0.05", "-0.10", "-0.15", 10);
        when(repo.findActiveRulesAt(any(Instant.class))).thenReturn(List.of(discountRule));

        PricePreviewResponse preview = service.previewPrice(new BigDecimal("50000"), null);
        assertEquals(new BigDecimal("50000.00"), preview.sellingPrice());
        assertEquals(BigDecimal.ZERO, preview.effectiveRate());
    }

    @Test
    void aggregatesProductMinAndMaxPriceFromActiveSkusOnly() {
        PricingRuleRepository repo = mock(PricingRuleRepository.class);
        DynamicPricingService service = new DynamicPricingService(repo);
        when(repo.findActiveRulesAt(any(Instant.class)))
                .thenReturn(List.of(rule("Default +15%", null, "0.15", "0.00", "0.00", 0)));

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(UUID.randomUUID());
        product.setName("Laptop Pro");
        product.setStatus(ProductStatus.ACTIVE);

        Sku cheap = sku(product, "SKU-CHEAP", "100.00", SkuStatus.ACTIVE);
        Sku expensive = sku(product, "SKU-EXP", "200.00", SkuStatus.ACTIVE);
        Sku hidden = sku(product, "SKU-OFF", "999.00", SkuStatus.INACTIVE);
        product.setSkus(List.of(cheap, expensive, hidden));

        ProductResponse response = service.toEnrichedProductResponse(product, ProductMapper.INSTANCE);
        assertEquals(new BigDecimal("115.00"), response.minPrice());
        assertEquals(new BigDecimal("230.00"), response.maxPrice());
        assertEquals(3, response.skus().size());
        assertNull(service.toEnrichedProductResponse(emptyProduct(), ProductMapper.INSTANCE).minPrice());
    }

    private PricingRule rule(String name, Category category, String margin, String season, String market, int priority) {
        PricingRule rule = new PricingRule();
        rule.setId(UUID.randomUUID());
        rule.setName(name);
        rule.setCategory(category);
        rule.setMarginRate(new BigDecimal(margin));
        rule.setSeasonFactor(new BigDecimal(season));
        rule.setMarketAdjustmentRate(new BigDecimal(market));
        rule.setPriority(priority);
        rule.setIsActive(true);
        return rule;
    }

    private Sku sku(Product product, String code, String originalPrice, SkuStatus status) {
        Sku sku = new Sku();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product);
        sku.setAttributes("{\"label\":\"" + code + "\"}");
        sku.setOriginalPrice(new BigDecimal(originalPrice));
        sku.setStatus(status);
        return sku;
    }

    private Product emptyProduct() {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(UUID.randomUUID());
        product.setName("Empty");
        product.setStatus(ProductStatus.DRAFT);
        return product;
    }
}
