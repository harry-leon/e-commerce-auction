package com.nexus.catalog.service;

import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.response.PricePreviewResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.PricingRule;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.PricingRuleRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Công cụ tính giá bán động cho từng SKU và tổng hợp khoảng giá (minPrice, maxPrice) cho Product.
 * Quy tắc: giá bán = originalPrice * (1 + max(0, marginRate + seasonFactor + marketAdjustmentRate)),
 * ưu tiên quy tắc đang hiệu lực khớp danh mục trực tiếp -> danh mục cha/ông -> quy tắc toàn hệ thống.
 */
@Service
public class DynamicPricingService {

    private static final int MONEY_SCALE = 2;
    private static final Comparator<PricingRule> RULE_ORDER = Comparator
            .comparing(PricingRule::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(PricingRule::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final PricingRuleRepository pricingRuleRepository;

    public DynamicPricingService(@Nullable PricingRuleRepository pricingRuleRepository) {
        this.pricingRuleRepository = pricingRuleRepository;
    }

    /** Nạp danh sách quy tắc đang hiệu lực một lần cho cả batch xử lý để tránh truy vấn N+1. */
    public List<PricingRule> loadActiveRules(Instant now) {
        if (pricingRuleRepository == null) {
            return List.of();
        }
        return pricingRuleRepository.findActiveRulesAt(now);
    }

    /** Tính giá bán động hiện tại cho một SKU dựa trên quy tắc đang hiệu lực. */
    public BigDecimal calculateSellingPrice(Sku sku) {
        Instant now = Instant.now();
        return calculateSellingPrice(sku, loadActiveRules(now));
    }

    /** Tính giá bán động cho SKU với danh sách quy tắc đã nạp sẵn. */
    public BigDecimal calculateSellingPrice(Sku sku, List<PricingRule> activeRules) {
        if (sku == null || sku.getOriginalPrice() == null) {
            return null;
        }
        Category category = sku.getProduct() != null ? sku.getProduct().getCategory() : null;
        return previewPrice(sku.getOriginalPrice(), category, activeRules).sellingPrice();
    }

    /** Tính giá xem trước và trả kèm thông tin quy tắc đã áp dụng cho API preview / Seller UI. */
    public PricePreviewResponse previewPrice(BigDecimal originalPrice, @Nullable Category category) {
        return previewPrice(originalPrice, category, loadActiveRules(Instant.now()));
    }

    /** Tính giá bán động thuần từ originalPrice + danh mục + danh sách quy tắc đang hiệu lực. */
    public PricePreviewResponse previewPrice(
            BigDecimal originalPrice,
            @Nullable Category category,
            List<PricingRule> activeRules) {
        if (originalPrice == null) {
            throw new IllegalArgumentException("originalPrice is required for dynamic pricing");
        }
        BigDecimal normalizedOriginal = originalPrice.max(BigDecimal.ZERO);
        PricingRule selected = selectRule(category, activeRules);
        if (selected == null) {
            BigDecimal unchanged = normalizedOriginal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            return new PricePreviewResponse(
                    normalizedOriginal, unchanged, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal margin = zeroIfNull(selected.getMarginRate());
        BigDecimal season = zeroIfNull(selected.getSeasonFactor());
        BigDecimal market = zeroIfNull(selected.getMarketAdjustmentRate());
        BigDecimal combinedRate = margin.add(season).add(market);
        // Chặn tỉ lệ cộng dồn âm để giá bán không bao giờ thấp hơn giá nhập tối thiểu (minPrice).
        BigDecimal effectiveRate = combinedRate.compareTo(BigDecimal.ZERO) < 0
                ? BigDecimal.ZERO
                : combinedRate;
        BigDecimal calculated = normalizedOriginal.multiply(BigDecimal.ONE.add(effectiveRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal minScaled = normalizedOriginal.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal finalPrice = calculated.compareTo(minScaled) < 0 ? minScaled : calculated;
        return new PricePreviewResponse(
                normalizedOriginal,
                finalPrice,
                selected.getId(),
                selected.getName(),
                margin,
                season,
                market,
                effectiveRate);
    }

    /** Chuyển Product entity sang ProductResponse có giá động cho từng SKU và khoảng minPrice/maxPrice. */
    public ProductResponse toEnrichedProductResponse(Product product, ProductMapper mapper) {
        return toEnrichedProductResponse(product, mapper, loadActiveRules(Instant.now()));
    }

    /** Chuyển Product entity sang ProductResponse dùng lại danh sách quy tắc đã nạp theo trang. */
    public ProductResponse toEnrichedProductResponse(
            Product product,
            ProductMapper mapper,
            List<PricingRule> activeRules) {
        ProductResponse base = mapper.toResponse(product);
        List<SkuResponse> pricedSkus = product.getSkus() == null
                ? List.of()
                : product.getSkus().stream()
                        .map(sku -> toEnrichedSkuResponse(sku, mapper, activeRules))
                        .toList();
        // Khoảng giá sản phẩm tổng hợp từ các SKU đang ACTIVE (đang mở bán).
        List<BigDecimal> activePrices = pricedSkus.stream()
                .filter(sku -> sku.status() == SkuStatus.ACTIVE && sku.price() != null)
                .map(SkuResponse::price)
                .toList();
        BigDecimal minPrice = activePrices.stream().min(BigDecimal::compareTo).orElse(null);
        BigDecimal maxPrice = activePrices.stream().max(BigDecimal::compareTo).orElse(null);
        return new ProductResponse(
                base.id(),
                base.sellerId(),
                base.categoryId(),
                base.name(),
                base.description(),
                base.status(),
                base.auctionEnabled(),
                base.createdAt(),
                minPrice,
                maxPrice,
                pricedSkus,
                base.images());
    }

    /** Chuyển một Sku entity sang SkuResponse với trường price là giá bán động tính tại thời điểm gọi. */
    public SkuResponse toEnrichedSkuResponse(Sku sku, ProductMapper mapper) {
        return toEnrichedSkuResponse(sku, mapper, loadActiveRules(Instant.now()));
    }

    /** Chuyển một Sku entity sang SkuResponse dùng danh sách quy tắc đã nạp sẵn. */
    public SkuResponse toEnrichedSkuResponse(
            Sku sku,
            ProductMapper mapper,
            List<PricingRule> activeRules) {
        SkuResponse base = mapper.toSku(sku);
        BigDecimal sellingPrice = calculateSellingPrice(sku, activeRules);
        return new SkuResponse(
                base.id(),
                base.skuCode(),
                base.attributes(),
                sellingPrice,
                base.originalPrice(),
                base.status());
    }

    @Nullable
    private PricingRule selectRule(@Nullable Category category, List<PricingRule> activeRules) {
        if (activeRules == null || activeRules.isEmpty()) {
            return null;
        }
        // Duyệt từ danh mục cụ thể -> cha -> ông để kế thừa quy tắc danh mục nếu danh mục con chưa cấu hình riêng.
        Category cursor = category;
        int depth = 0;
        while (cursor != null && depth < 3) {
            UUID targetCategoryId = cursor.getId();
            if (targetCategoryId != null) {
                PricingRule match = activeRules.stream()
                        .filter(rule -> rule.getCategory() != null
                                && Objects.equals(rule.getCategory().getId(), targetCategoryId))
                        .min(RULE_ORDER)
                        .orElse(null);
                if (match != null) {
                    return match;
                }
            }
            cursor = cursor.getParent();
            depth++;
        }
        return activeRules.stream()
                .filter(rule -> rule.getCategory() == null)
                .min(RULE_ORDER)
                .orElse(null);
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
