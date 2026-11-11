package com.nexus.catalog.service;

import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.SkuRepository;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read model cho Cart lấy giá động/trạng thái SKU thật; tồn kho thuộc Fulfillment nên stockQuantity để null. */
@Service
public class SkuLookupService {
    private final SkuRepository skus;
    private final DynamicPricingService pricingService;

    public SkuLookupService(SkuRepository skus) {
        this(skus, new DynamicPricingService(null));
    }

    /** Khởi tạo dịch vụ tra cứu SKU cùng DynamicPricingService để trả giá bán động. */
    @Autowired
    public SkuLookupService(SkuRepository skus, DynamicPricingService pricingService) {
        this.skus = skus;
        this.pricingService = pricingService != null ? pricingService : new DynamicPricingService(null);
    }

    /** Đọc SKU/product trong transaction; trả sellerId thật để Checkout chặn tự mua và đơn nhiều shop. */
    @Transactional(readOnly = true)
    public SkuSnapshot get(UUID id) {
        var sku = skus.findById(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy SKU"));
        var product = sku.getProduct();
        String image = product.getImages().isEmpty() ? null : product.getImages().getFirst().getUrl();
        BigDecimal dynamicPrice = pricingService.calculateSellingPrice(sku);
        return new SkuSnapshot(id, sku.getSkuCode(), product.getName(), image, dynamicPrice, null,
                sku.getStatus() == SkuStatus.ACTIVE && product.getStatus() == ProductStatus.ACTIVE, product.getSellerId());
    }

    /** Contract đọc giữa Catalog/Commerce; sellerId thuộc product, null stock chưa được kiểm tra tại Fulfillment. */
    public record SkuSnapshot(UUID skuId, String skuCode, String productName, String thumbnailUrl,
                              BigDecimal price, Integer stockQuantity, Boolean isActive, UUID sellerId) { }
}
