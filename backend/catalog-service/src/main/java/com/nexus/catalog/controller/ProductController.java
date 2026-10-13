package com.nexus.catalog.controller;

import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.PageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.model.enum_.ProductStatus;
import com.nexus.catalog.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
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
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Product", description = "Product & SKU catalog management APIs")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

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

    @Operation(summary = "Create a new product", description = "Creates a product in DRAFT status with initial SKUs and images (max 5).")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Product created successfully",
            content = @Content(schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation or business error",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden - missing required privileges",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return productService.create(getCurrentSellerId(), request);
    }

    @Operation(summary = "Update product details", description = "Updates title, description, category, and digital flag.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product updated successfully",
            content = @Content(schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public ProductResponse update(
            @PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @Operation(summary = "Delete a product", description = "Deletes a product by its ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Product deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        productService.delete(id);
    }

    @Operation(summary = "Get product by ID", description = "Retrieves complete product details including SKUs and images.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product retrieved successfully",
            content = @Content(schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/{id}")
    public ProductResponse getById(@PathVariable UUID id) {
        return productService.getById(id);
    }

    @Operation(summary = "List/search products", description = "Retrieves paginated list of products, optionally filtered by status.")
    @ApiResponse(responseCode = "200", description = "Paginated list of products")
    @GetMapping
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) ProductStatus status,
            Pageable pageable) {
        return PageResponse.from(productService.list(status, pageable));
    }

    @Operation(summary = "List products by seller", description = "Retrieves paginated list of products belonging to a seller.")
    @ApiResponse(responseCode = "200", description = "Paginated list of seller products")
    @GetMapping("/seller/{sellerId}")
    public PageResponse<ProductResponse> listBySeller(
            @PathVariable UUID sellerId, Pageable pageable) {
        return PageResponse.from(productService.listBySeller(sellerId, pageable));
    }

    @Operation(summary = "Change product status", description = "Transition status between DRAFT, ACTIVE, and INACTIVE.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated successfully",
            content = @Content(schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid status transition",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - requires ADMIN or SUPPORT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAnyAuthority('PRODUCT.UPDATE', 'ADMIN', 'SUPPORT', 'ROLE_ADMIN', 'ROLE_SUPPORT')")
    public ProductResponse changeStatus(
            @PathVariable UUID id, @RequestParam ProductStatus target) {
        return productService.changeStatus(id, target);
    }

    @Operation(summary = "Approve product for publishing", description = "Admin or Support approves product from DRAFT to ACTIVE.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Product approved and activated",
            content = @Content(schema = @Schema(implementation = ProductResponse.class))),
        @ApiResponse(responseCode = "400", description = "Product not in DRAFT status",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - requires ADMIN or SUPPORT",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAnyAuthority('PRODUCT.UPDATE', 'ADMIN', 'SUPPORT', 'ROLE_ADMIN', 'ROLE_SUPPORT')")
    public ProductResponse approve(@PathVariable UUID id) {
        return productService.approve(id);
    }

    // --- SKU endpoints ---

    @Operation(summary = "Add SKU to product", description = "Adds a new SKU variant to an existing product.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "SKU created successfully",
            content = @Content(schema = @Schema(implementation = SkuResponse.class))),
        @ApiResponse(responseCode = "400", description = "Duplicate SKU code or invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{productId}/skus")
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public SkuResponse addSku(
            @PathVariable UUID productId, @Valid @RequestBody SkuRequest request) {
        return productService.addSku(productId, request);
    }

    @Operation(summary = "Update SKU details", description = "Updates price, currency, quantity, and attributes of a SKU.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "SKU updated successfully",
            content = @Content(schema = @Schema(implementation = SkuResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public SkuResponse updateSku(
            @PathVariable UUID skuId, @Valid @RequestBody SkuRequest request) {
        return productService.updateSku(skuId, request);
    }

    @Operation(summary = "Delete SKU", description = "Deletes a SKU variant by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "SKU deleted successfully"),
        @ApiResponse(responseCode = "404", description = "SKU not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/skus/{skuId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSku(@PathVariable UUID skuId) {
        productService.deleteSku(skuId);
    }

    // --- Image endpoints ---

    @Operation(summary = "Add image to product", description = "Adds an image to product. Enforces maximum 5 images per product.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Image added successfully",
            content = @Content(schema = @Schema(implementation = ImageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Image limit exceeded (>5 images) or invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "Product not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PostMapping("/{productId}/images")
    @PreAuthorize("hasAuthority('PRODUCT.CREATE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public ImageResponse addImage(
            @PathVariable UUID productId, @Valid @RequestBody ImageRequest request) {
        return productService.addImage(productId, request);
    }

    @Operation(summary = "Update product image", description = "Updates URL, alt text, or primary status of an image.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Image updated successfully",
            content = @Content(schema = @Schema(implementation = ImageResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid request",
            content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = "404", description = "Image not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @PutMapping("/images/{imageId}")
    @PreAuthorize("hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    public ImageResponse updateImage(
            @PathVariable UUID imageId, @Valid @RequestBody ImageRequest request) {
        return productService.updateImage(imageId, request);
    }

    @Operation(summary = "Delete product image", description = "Deletes a product image by ID.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Image deleted successfully"),
        @ApiResponse(responseCode = "404", description = "Image not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasAuthority('PRODUCT.DELETE') or hasAuthority('PRODUCT.UPDATE') or hasRole('SELLER') or hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(@PathVariable UUID imageId) {
        productService.deleteImage(imageId);
    }
}
