package com.nexus.catalog.service;

import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.PricingRule;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.ProductRepository;
import com.nexus.common.exception.BusinessException;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tìm kiếm sản phẩm bằng JPA Specification để ghép các bộ lọc tùy chọn trong một truy vấn DB.
 * Luồng: kiểm tra tham số → tạo điều kiện tên/danh mục/SKU → tính giá động → lọc khoảng giá & phân trang.
 */
@Service
public class ProductSearchService {
    private final ProductRepository products;
    private final ProductMapper mapper;
    private final DynamicPricingService pricingService;

    public ProductSearchService(ProductRepository products, ProductMapper mapper) {
        this(products, mapper, new DynamicPricingService(null));
    }

    /** Khởi tạo dịch vụ tìm kiếm có tích hợp DynamicPricingService để lọc và tổng hợp khoảng giá động. */
    @Autowired
    public ProductSearchService(
            ProductRepository products,
            ProductMapper mapper,
            DynamicPricingService pricingService) {
        this.products = products;
        this.mapper = mapper;
        this.pricingService = pricingService != null ? pricingService : new DynamicPricingService(null);
    }

    /**
     * Mặc định lấy sản phẩm ACTIVE; controller kiểm tra quyền nếu client yêu cầu trạng thái khác.
     * Khoảng giá áp dụng cho SKU ACTIVE sau khi tính giá động; transaction đọc nạp sẵn quy tắc theo batch.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String q, UUID categoryId, BigDecimal minPrice,
            BigDecimal maxPrice, Boolean auctionEnabled, ProductStatus status, Pageable pageable) {
        // Chỉ nhận trường sort được hỗ trợ, tránh lỗi truy vấn do client gửi tên thuộc tính không tồn tại.
        if (pageable.getSort().stream().anyMatch(order -> !List.of("name", "createdAt", "id").contains(order.getProperty()))) {
            throw new BusinessException("Supported sort fields: name, createdAt, id");
        }
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)
                || (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0)) {
            throw new BusinessException("Invalid price range");
        }
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            filters.add(cb.equal(root.get("status"), status == null ? ProductStatus.ACTIVE : status));
            if (q != null && !q.isBlank()) {
                String trimmed = q.trim();
                // Escape ký tự LIKE để tìm dấu %, _ và \ đúng nghĩa văn bản, không biến chúng thành wildcard.
                String escaped = trimmed.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                Predicate phraseMatch = cb.or(
                        cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')
                );

                // Tách từ khóa tìm kiếm (Multi-token search) để các từ như "đèn led" vẫn tìm ra "Đèn bàn học LED"
                String[] tokens = trimmed.toLowerCase(Locale.ROOT).split("\\s+");
                if (tokens.length > 1) {
                    List<Predicate> tokenPredicates = new ArrayList<>();
                    for (String token : tokens) {
                        if (token.isBlank()) continue;
                        String escapedToken = token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                        String tokenPattern = "%" + escapedToken + "%";
                        tokenPredicates.add(cb.or(
                                cb.like(cb.lower(root.get("name")), tokenPattern, '\\'),
                                cb.like(cb.lower(root.get("description")), tokenPattern, '\\')
                        ));
                    }
                    Predicate allTokensMatch = cb.and(tokenPredicates.toArray(Predicate[]::new));
                    filters.add(cb.or(phraseMatch, allTokensMatch));
                } else {
                    filters.add(phraseMatch);
                }
            }
            if (categoryId != null) {
                // Cây tối đa ba cấp: sản phẩm khớp nếu danh mục của nó, cha hoặc ông khớp UUID bộ lọc.
                var category = root.<Product, Category>join("category", JoinType.LEFT);
                var parent = category.<Category, Category>join("parent", JoinType.LEFT);
                var grandparent = parent.<Category, Category>join("parent", JoinType.LEFT);
                filters.add(cb.or(cb.equal(category.get("id"), categoryId),
                        cb.equal(parent.get("id"), categoryId), cb.equal(grandparent.get("id"), categoryId)));
            }
            if (minPrice != null || maxPrice != null) {
                // Sơ lọc SKU ACTIVE ở DB; do sellingPrice >= originalPrice nên originalPrice <= maxPrice không bỏ sót kết quả.
                var sku = root.<Product, Sku>join("skus");
                filters.add(cb.equal(sku.get("status"), SkuStatus.ACTIVE));
                if (maxPrice != null) {
                    filters.add(cb.lessThanOrEqualTo(sku.get("originalPrice"), maxPrice));
                }
            }
            if (auctionEnabled != null) {
                filters.add(cb.equal(root.get("auctionEnabled"), auctionEnabled));
            }
            // Một sản phẩm có thể có nhiều SKU khớp; loại trùng trước khi tính tổng và phân trang.
            query.distinct(true);
            return cb.and(filters.toArray(Predicate[]::new));
        };
        List<PricingRule> activeRules = pricingService.loadActiveRules(Instant.now());
        if (minPrice == null && maxPrice == null) {
            return products.findAll(spec, pageable)
                    .map(p -> pricingService.toEnrichedProductResponse(p, mapper, activeRules));
        }
        // Khi có lọc theo giá động, đánh giá khoảng giá chính xác trên cùng một SKU ACTIVE trước khi cắt trang.
        List<ProductResponse> matched = products.findAll(spec, pageable.getSort()).stream()
                .map(p -> pricingService.toEnrichedProductResponse(p, mapper, activeRules))
                .filter(p -> matchesPriceRange(p, minPrice, maxPrice))
                .toList();
        if (pageable.isUnpaged()) {
            return new PageImpl<>(matched, pageable, matched.size());
        }
        int start = (int) Math.min(pageable.getOffset(), matched.size());
        int end = Math.min(start + pageable.getPageSize(), matched.size());
        return new PageImpl<>(matched.subList(start, end), pageable, matched.size());
    }

    private boolean matchesPriceRange(ProductResponse product, BigDecimal minPrice, BigDecimal maxPrice) {
        if (product.skus() == null) {
            return false;
        }
        return product.skus().stream()
                .filter(sku -> sku.status() == SkuStatus.ACTIVE && sku.price() != null)
                .anyMatch(sku -> (minPrice == null || sku.price().compareTo(minPrice) >= 0)
                        && (maxPrice == null || sku.price().compareTo(maxPrice) <= 0));
    }
}
