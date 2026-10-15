package com.nexus.catalog.controller;

import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.nexus.catalog.dto.response.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product", description = "Product & SKU catalog management APIs")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    // Get seller id from authentication
    private UUID getCurrentSellerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            try {
                return UUID.fromString(auth.getName());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Invalid seller ID in authentication");
            }
        }
        throw new BusinessException("Seller not authenticated");
    }

    // --- Product Endpoints ---
    // Create Product
    @Operation(summary = "Create a new product", description = "Creates a product in DRAFT status with initial SKUs and images (max 5).")
    @PostMapping
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        return ApiResponse.ok(productService.create(getCurrentSellerId(), request));
    }

    // Update product
    @Operation(summary = "Update product details", description = "Updates title, description, category, and digital flag.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public ApiResponse<ProductResponse> update(
        @PathVariable UUID id,
        @Valid @RequestBody ProductRequest request
    ) {
        return ApiResponse.ok(productService.update(id, request));
    }

    @Operation(summary = "Delete a product", description = "Deletes a product by its ID.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        productService.delete(id);
    }

    // Get product by ID
    @Operation(summary = "Get product by ID", description = "Retrieves complete product details including SKUs and images.")
    @GetMapping("/{id}")
    public ApiResponse<ProductResponse> getById(@PathVariable UUID id) {
        return ApiResponse.ok(productService.getById(id));
    }

    // List all products
    @Operation(summary = "List/search products", description = "Retrieves paginated list of products, optionally filtered by status.")
    @GetMapping
    public ApiResponse<List<ProductResponse>> list(
        @RequestParam(required = false) ProductStatus status,
        Pageable pageable
    ) {
        return ApiResponse.ok(productService.list(status, pageable));
    }

    // List products by seller
    @Operation(summary = "List products by seller", description = "Retrieves paginated list of products belonging to a seller.")
    @GetMapping("/seller/{sellerId}")
    public ApiResponse<List<ProductResponse>> listBySeller(
        @PathVariable UUID sellerId,
        Pageable pageable
    ) {
        return ApiResponse.ok(productService.listBySeller(sellerId, pageable));
    }

    // Change product status
    @Operation(summary = "Change product status", description = "Transition status between DRAFT, ACTIVE, and INACTIVE.")
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAnyAuthority('PRODUCT.UPDATE', 'ADMIN', 'SUPPORT', 'ROLE_ADMIN', 'ROLE_SUPPORT')")
    public ApiResponse<ProductResponse> changeStatus(
        @PathVariable UUID id,
        @RequestParam ProductStatus target
    ) {
        return ApiResponse.ok(productService.changeStatus(id, target));
    }

    // Approve product for publishing (Only admin and support)
    @Operation(summary = "Approve product for publishing", description = "Admin or Support approves product from DRAFT to ACTIVE.")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAnyAuthority('PRODUCT.UPDATE', 'ADMIN', 'SUPPORT', 'ROLE_ADMIN', 'ROLE_SUPPORT')")
    public ApiResponse<ProductResponse> approve(@PathVariable UUID id) {
        return ApiResponse.ok(productService.approve(id));
    }

    // --- SKU Endpoints ---

    // Add SKU to product
    @Operation(summary = "Add SKU to product", description = "Adds a new SKU variant to an existing product.")
    @PostMapping("/{productId}/skus")
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkuResponse> addSku(
        @PathVariable UUID productId,
        @Valid @RequestBody SkuRequest request
    ) {
        return ApiResponse.ok(productService.addSku(productId, request));
    }

    // Update SKU details
    @Operation(summary = "Update SKU details", description = "Updates price, currency, quantity, and attributes of a SKU.")
    @PutMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public ApiResponse<SkuResponse> updateSku(
        @PathVariable UUID skuId,
        @Valid @RequestBody SkuRequest request
    ) {
        return ApiResponse.ok(productService.updateSku(skuId, request));
    }

    // Delete SKU
    @Operation(summary = "Delete SKU", description = "Deletes a SKU variant by ID.")
    @DeleteMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSku(@PathVariable UUID skuId) {
        productService.deleteSku(skuId);
    }

    // --- Image Endpoints ---

    // Add image to product
    @Operation(summary = "Add image to product", description = "Adds an image to product. Enforces maximum 5 images per product.")
    @PostMapping("/{productId}/images")
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImageResponse> addImage(
        @PathVariable UUID productId,
        @Valid @RequestBody ImageRequest request
    ) {
        return ApiResponse.ok(productService.addImage(productId, request));
    }

    // Update product image
    @Operation(summary = "Update product image", description = "Updates URL, alt text, or primary status of an image.")
    @PutMapping("/images/{imageId}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public ApiResponse<ImageResponse> updateImage(
        @PathVariable UUID imageId,
        @Valid @RequestBody ImageRequest request
    ) {
        return ApiResponse.ok(productService.updateImage(imageId, request));
    }

    // Delete product image
    @Operation(summary = "Delete product image", description = "Deletes a product image by ID.")
    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable UUID imageId) {
        productService.deleteImage(imageId);
    }
}
