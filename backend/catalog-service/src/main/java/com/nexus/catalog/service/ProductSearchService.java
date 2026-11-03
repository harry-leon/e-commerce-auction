package com.nexus.catalog.service;

import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.common.exception.BusinessException;
import com.nexus.catalog.repository.ProductRepository;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tìm kiếm sản phẩm bằng JPA Specification để ghép các bộ lọc tùy chọn trong một truy vấn DB.
 * Luồng: kiểm tra tham số → tạo điều kiện tên/danh mục/SKU → phân trang → ánh xạ sang ProductResponse.
 */
@Service
public class ProductSearchService {
    private final ProductRepository products;
    private final ProductMapper mapper;

    public ProductSearchService(ProductRepository products, ProductMapper mapper) {
        this.products = products;
        this.mapper = mapper;
    }

    /**
     * Mặc định lấy sản phẩm ACTIVE; controller kiểm tra quyền nếu client yêu cầu trạng thái khác.
     * Khoảng giá áp dụng cho SKU ACTIVE; transaction đọc giữ các quan hệ lazy khi mapper dựng DTO.
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
                // Escape ký tự LIKE để tìm dấu %, _ và \ đúng nghĩa văn bản, không biến chúng thành wildcard.
                String escaped = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\")
                        .replace("%", "\\%").replace("_", "\\_");
                String pattern = "%" + escaped + "%";
                filters.add(cb.or(cb.like(cb.lower(root.get("name")), pattern, '\\'),
                        cb.like(cb.lower(root.get("description")), pattern, '\\')));
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
                // Hai cận giá cùng áp dụng lên một SKU ACTIVE, không lấy giá thấp/cao từ hai SKU khác nhau.
                var sku = root.<Product, Sku>join("skus");
                filters.add(cb.equal(sku.get("status"), SkuStatus.ACTIVE));
                if (minPrice != null) {
                    filters.add(cb.greaterThanOrEqualTo(sku.get("price"), minPrice));
                }
                if (maxPrice != null) {
                    filters.add(cb.lessThanOrEqualTo(sku.get("price"), maxPrice));
                }
            }
            if (auctionEnabled != null) {
                filters.add(cb.equal(root.get("auctionEnabled"), auctionEnabled));
            }
            // Một sản phẩm có thể có nhiều SKU khớp; loại trùng trước khi tính tổng và phân trang.
            query.distinct(true);
            return cb.and(filters.toArray(Predicate[]::new));
        };
        return products.findAll(spec, pageable).map(mapper::toResponse);
    }
}
