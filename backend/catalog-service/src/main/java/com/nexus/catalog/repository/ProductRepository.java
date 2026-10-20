package com.nexus.catalog.repository;

import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.ProductImage;
import com.nexus.catalog.enums.ProductStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** JpaSpecificationExecutor ghép bộ lọc tìm kiếm; các truy vấn dưới đây hỗ trợ CRUD và quy tắc xóa danh mục. */
public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
    Page<Product> findBySellerId(UUID sellerId, Pageable pageable);
    // Truy vấn Image trực tiếp bằng JPQL; tránh để Spring suy diễn phương thức thành truy vấn trả entity Product.
    @org.springframework.data.jpa.repository.Query("select i from ProductImage i where i.id = :imageId")
    Optional<ProductImage> findImagesById(@org.springframework.data.repository.query.Param("imageId") UUID imageId);
    boolean existsByCategoryId(UUID categoryId);
    long countByCategoryId(UUID categoryId);
}
