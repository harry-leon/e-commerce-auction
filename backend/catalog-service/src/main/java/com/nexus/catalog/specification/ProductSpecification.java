package com.nexus.catalog.specification;

import com.nexus.catalog.dto.request.ProductFilterRequest;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public class ProductSpecification {
    private ProductSpecification() {}

    public static Specification<Product> build(ProductFilterRequest request) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            query.distinct(true); // tranh kq sp trung lap

            if (request == null) {
                return cb.equal(root.get("status"), ProductStatus.ACTIVE);
            }

            // 1. Lọc theo trạng thái (Mặc định cho người mua tìm kiếm là ACTIVE)
            if (request.status() != null) {
                predicates.add(cb.equal(root.get("status"), request.status()));
            } else {
                predicates.add(cb.equal(root.get("status"), ProductStatus.ACTIVE));
            }

            // 2. Lọc theo Danh mục (categoryId)
            if (request.categoryId() != null) {
                predicates.add(cb.equal(root.get("category").get("id"), request.categoryId()));
            }

            // 3. Tìm kiếm theo từ khóa (name của sản phẩm HOẶC name của category)
            if (StringUtils.hasText(request.keyword())) {
                String pattern = "%" + request.keyword().trim().toLowerCase() + "%";
                Join<Product, Category> categoryJoin = root.join("category", JoinType.LEFT);
                Predicate nameLike = cb.like(cb.lower(root.get("name")), pattern);
                Predicate categoryLike = cb.like(cb.lower(categoryJoin.get("name")), pattern);
                predicates.add(cb.or(nameLike, categoryLike));
            }

            // 4. Sơ lọc SKU ACTIVE ở DB; giá bán động luôn >= originalPrice nên originalPrice <= maxPrice an toàn.
            if (request.minPrice() != null || request.maxPrice() != null) {
                Join<Product, Sku> skuJoin = root.join("skus", JoinType.INNER);
                predicates.add(cb.equal(skuJoin.get("status"), SkuStatus.ACTIVE));
                if (request.maxPrice() != null) {
                    predicates.add(cb.lessThanOrEqualTo(skuJoin.get("originalPrice"), request.maxPrice()));
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
