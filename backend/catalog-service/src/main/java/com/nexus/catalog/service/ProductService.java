package com.nexus.catalog.service;

import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductFilterRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.enums.ProductStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {
    ProductResponse create(UUID sellerId, ProductRequest request);
    ProductResponse update(UUID id, ProductRequest request);
    void delete(UUID id);
    ProductResponse getById(UUID id);
    Page<ProductResponse> list(ProductStatus status, Pageable pageable);
    Page<ProductResponse> listBySeller(UUID sellerId, Pageable pageable);
    ProductResponse changeStatus(UUID id, ProductStatus target);
    ProductResponse approve(UUID id);

    // SKU operations
    SkuResponse addSku(UUID productId, SkuRequest request);
    SkuResponse updateSku(UUID skuId, SkuRequest request);
    void deleteSku(UUID skuId);

    // Image operations
    ImageResponse addImage(UUID productId, ImageRequest request);
    ImageResponse updateImage(UUID imageId, ImageRequest request);
    void deleteImage(UUID imageId);

    // Specification
    Page<ProductResponse> search(ProductFilterRequest request, Pageable pageable);
}
