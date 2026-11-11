package com.nexus.catalog.service.impl;

import com.nexus.catalog.config.CatalogProperties;
import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductFilterRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.catalog.entity.PricingRule;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.ProductImage;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.ProductImageRepository;
import com.nexus.catalog.repository.ProductRepository;
import com.nexus.catalog.repository.SkuRepository;
import com.nexus.catalog.service.CatalogEventOutboxService;
import com.nexus.catalog.service.DynamicPricingService;
import com.nexus.catalog.service.ProductService;
import com.nexus.catalog.specification.ProductSpecification;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {
    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductMapper productMapper;
    private final Validator validator;
    // Cấu hình tham số nghiệp vụ catalog từ Spring Cloud Config Server / application.yml
    private final CatalogProperties catalogProperties;
    private final DynamicPricingService dynamicPricingService;
    private final CatalogEventOutboxService catalogEventOutboxService;

    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            ProductMapper productMapper,
            Validator validator) {
        this(productRepository, skuRepository, categoryRepository, productImageRepository,
                productMapper, validator, new CatalogProperties(), new DynamicPricingService(null), null);
    }

    /** Khởi tạo ProductServiceImpl giữ tương thích constructor cũ kèm CatalogProperties. */
    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            ProductMapper productMapper,
            Validator validator,
            CatalogProperties catalogProperties) {
        this(productRepository, skuRepository, categoryRepository, productImageRepository,
                productMapper, validator, catalogProperties, new DynamicPricingService(null), null);
    }

    /** Khởi tạo ProductServiceImpl tương thích với phiên bản chưa truyền Outbox Service. */
    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            ProductMapper productMapper,
            Validator validator,
            CatalogProperties catalogProperties,
            DynamicPricingService dynamicPricingService) {
        this(productRepository, skuRepository, categoryRepository, productImageRepository,
                productMapper, validator, catalogProperties, dynamicPricingService, null);
    }

    /** Khởi tạo ProductServiceImpl đầy đủ dependencies gồm DynamicPricingService và Outbox phát ProductSkuCreated. */
    @Autowired
    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            ProductMapper productMapper,
            Validator validator,
            CatalogProperties catalogProperties,
            DynamicPricingService dynamicPricingService,
            @Nullable CatalogEventOutboxService catalogEventOutboxService) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.productImageRepository = productImageRepository;
        this.productMapper = productMapper != null ? productMapper : ProductMapper.INSTANCE;
        this.validator = validator;
        this.catalogProperties = catalogProperties != null ? catalogProperties : new CatalogProperties();
        this.dynamicPricingService = dynamicPricingService != null
                ? dynamicPricingService
                : new DynamicPricingService(null);
        this.catalogEventOutboxService = catalogEventOutboxService;
    }

    @Override
    public ProductResponse create(UUID sellerId, ProductRequest request) {
        if (sellerId == null) {
            throw new BusinessException("Seller ID is required");
        }
        validateRequest(request);

        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));
        // Catalog chỉ sinh skuId UUID và lưu originalPrice; không nhận hoặc kiểm tra trùng skuCode.
        Product product = productMapper.toEntity(request);
        product.setSellerId(sellerId);
        product.setCategory(category);
        product.setSkus(buildSkus(request.skus(), product));
        if (request.images() != null && !request.images().isEmpty()) {
            product.setImages(buildImages(request.images(), product));
        }
        Product saved = productRepository.save(product);
        if (catalogEventOutboxService != null) {
            catalogEventOutboxService.enqueueSkuCreated(saved, saved.getSkus());
        }
        return dynamicPricingService.toEnrichedProductResponse(saved, productMapper);
    }

    @Override
    public ProductResponse update(UUID id, ProductRequest request) {
        validateRequest(request);
        Product product = findProductById(id);
        verifyProductOwnership(product);

        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + request.categoryId()));

        // Bảo toàn skuId của các biến thể hiện có khi cập nhật sản phẩm; chỉ tạo mới đối với SKU không truyền id.
        Map<UUID, Sku> existingById = new HashMap<>();
        for (Sku existing : product.getSkus()) {
            if (existing.getId() != null) {
                existingById.put(existing.getId(), existing);
            }
        }
        Set<UUID> retainedIds = new HashSet<>();
        List<Sku> newSkus = new ArrayList<>();
        for (SkuRequest skuReq : request.skus()) {
            if (skuReq.id() != null) {
                if (!retainedIds.add(skuReq.id())) {
                    throw new BusinessException("Duplicate SKU id in request: " + skuReq.id());
                }
                Sku target = existingById.get(skuReq.id());
                if (target == null) {
                    throw new BusinessException("SKU does not belong to product: " + skuReq.id());
                }
                productMapper.updateSkuFromRequest(skuReq, target);
            } else {
                Sku created = productMapper.toSkuEntity(skuReq);
                created.setProduct(product);
                newSkus.add(created);
            }
        }

        productMapper.updateProductFromRequest(request, product);
        product.setCategory(category);
        product.getSkus().removeIf(existing -> existing.getId() != null && !retainedIds.contains(existing.getId()));
        product.getSkus().addAll(newSkus);
        product.getImages().clear();
        if (request.images() != null && !request.images().isEmpty()) {
            product.getImages().addAll(buildImages(request.images(), product));
        }
        Product saved = productRepository.save(product);
        if (catalogEventOutboxService != null && !newSkus.isEmpty()) {
            List<Sku> emitted = saved.getSkus().stream()
                    .filter(sku -> sku.getId() != null && !retainedIds.contains(sku.getId()))
                    .toList();
            catalogEventOutboxService.enqueueSkuCreated(saved, emitted.isEmpty() ? newSkus : emitted);
        }
        return dynamicPricingService.toEnrichedProductResponse(saved, productMapper);
    }

    @Override
    public void delete(UUID id) {
        Product product = findProductById(id);
        verifyProductOwnership(product);
        productRepository.delete(product);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductResponse getById(UUID id) {
        Product product = findProductById(id);
        if (product.getStatus() != ProductStatus.ACTIVE) {
            // Chi tiết ACTIVE được đọc công khai; sản phẩm chưa công bố phải qua kiểm tra chủ sở hữu/quản trị.
            verifyProductOwnership(product);
        }
        return dynamicPricingService.toEnrichedProductResponse(product, productMapper);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(ProductStatus status, Pageable pageable) {
        List<PricingRule> activeRules = dynamicPricingService.loadActiveRules(Instant.now());
        if (status != null) {
            return productRepository.findByStatus(status, pageable)
                    .map(p -> dynamicPricingService.toEnrichedProductResponse(p, productMapper, activeRules));
        }
        return productRepository.findAll(pageable)
                .map(p -> dynamicPricingService.toEnrichedProductResponse(p, productMapper, activeRules));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> listBySeller(UUID sellerId, Pageable pageable) {
        List<PricingRule> activeRules = dynamicPricingService.loadActiveRules(Instant.now());
        return productRepository.findBySellerId(sellerId, pageable)
                .map(p -> dynamicPricingService.toEnrichedProductResponse(p, productMapper, activeRules));
    }

    @Override
    public ProductResponse changeStatus(UUID id, ProductStatus target) {
        if (target == null) {
            throw new BusinessException("Target status is required");
        }
        if (target == ProductStatus.ACTIVE) {
            throw new BusinessException(
                    "Product must be approved by Admin/Support to become ACTIVE");
        }
        Product product = findProductById(id);
        if (product.getStatus() == target) {
            throw new BusinessException("Product is already in status: " + target);
        }
        product.setStatus(target);
        return dynamicPricingService.toEnrichedProductResponse(productRepository.save(product), productMapper);
    }

    @Override
    public ProductResponse approve(UUID id) {
        Product product = findProductById(id);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            throw new BusinessException("Product is already ACTIVE");
        }
        product.setStatus(ProductStatus.ACTIVE);
        return dynamicPricingService.toEnrichedProductResponse(productRepository.save(product), productMapper);
    }

    @Override
    public SkuResponse addSku(UUID productId, SkuRequest request) {
        Product product = findProductById(productId);
        verifyProductOwnership(product);
        Set<UUID> beforeIds = product.getSkus().stream()
                .map(Sku::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Sku sku = productMapper.toSkuEntity(request);
        sku.setProduct(product);
        product.getSkus().add(sku);
        Product saved = productRepository.save(product);
        Sku persistedSku = saved.getSkus().stream()
                .filter(s -> s.getId() != null && !beforeIds.contains(s.getId()))
                .reduce((first, second) -> second)
                .orElse(sku);
        if (catalogEventOutboxService != null) {
            catalogEventOutboxService.enqueueSkuCreated(saved, List.of(persistedSku));
        }
        return dynamicPricingService.toEnrichedSkuResponse(persistedSku, productMapper);
    }

    @Override
    public SkuResponse updateSku(UUID skuId, SkuRequest request) {
        Sku sku = skuRepository.findById(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found: " + skuId));
        verifyProductOwnership(sku.getProduct());
        productMapper.updateSkuFromRequest(request, sku);
        return dynamicPricingService.toEnrichedSkuResponse(skuRepository.save(sku), productMapper);
    }

    @Override
    public void deleteSku(UUID skuId) {
        Sku sku = skuRepository.findById(skuId)
                .orElseThrow(() -> new ResourceNotFoundException("SKU not found: " + skuId));
        verifyProductOwnership(sku.getProduct());
        skuRepository.delete(sku);
    }

    @Override
    public ImageResponse addImage(UUID productId, ImageRequest request) {
        Product product = findProductById(productId);
        verifyProductOwnership(product);
        int maxImages = catalogProperties.getProduct().getMaxImages();
        if (product.getImages().size() >= maxImages) {
            throw new BusinessException("Product may have at most " + maxImages + " images");
        }
        ProductImage image = productMapper.toImageEntity(request);
        image.setProduct(product);
        product.getImages().add(image);
        productRepository.save(product);
        return productMapper.toImage(image);
    }

    @Override
    public ImageResponse updateImage(UUID imageId, ImageRequest request) {
        ProductImage image = (productImageRepository != null
                ? productImageRepository.findById(imageId)
                : productRepository.findImagesById(imageId))
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));
        verifyProductOwnership(image.getProduct());
        productMapper.updateImageFromRequest(request, image);
        productRepository.save(image.getProduct());
        return productMapper.toImage(image);
    }

    @Override
    public void deleteImage(UUID imageId) {
        ProductImage image = (productImageRepository != null
                ? productImageRepository.findById(imageId)
                : productRepository.findImagesById(imageId))
                .orElseThrow(() -> new ResourceNotFoundException("Image not found: " + imageId));
        verifyProductOwnership(image.getProduct());
        Product product = image.getProduct();
        product.getImages().remove(image);
        productRepository.save(product);
    }

    private Product findProductById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    private void validateRequest(ProductRequest request) {
        Set<ConstraintViolation<ProductRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            String msg = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new BusinessException(msg);
        }
    }

    private List<Sku> buildSkus(List<SkuRequest> skuRequests, Product product) {
        List<Sku> result = new ArrayList<>();
        for (SkuRequest req : skuRequests) {
            Sku sku = productMapper.toSkuEntity(req);
            sku.setProduct(product);
            result.add(sku);
        }
        return result;
    }

    private List<ProductImage> buildImages(List<ImageRequest> reqs, Product product) {
        List<ProductImage> result = new ArrayList<>();
        for (ImageRequest req : reqs) {
            ProductImage image = productMapper.toImageEntity(req);
            image.setProduct(product);
            result.add(image);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> search(ProductFilterRequest request, Pageable pageable) {
        // Validate logic if have min and max
        if (request != null && request.minPrice() != null && request.maxPrice() != null) {
            if (request.minPrice().compareTo(request.maxPrice()) > 0) {
                throw new BusinessException("minPrice cannot be greater than maxPrice");
            }
        }

        Specification<Product> spec = ProductSpecification.build(request);
        List<PricingRule> activeRules = dynamicPricingService.loadActiveRules(Instant.now());
        boolean hasPriceFilter = request != null && (request.minPrice() != null || request.maxPrice() != null);
        if (!hasPriceFilter) {
            return productRepository.findAll(spec, pageable)
                    .map(p -> dynamicPricingService.toEnrichedProductResponse(p, productMapper, activeRules));
        }
        // Khi có lọc minPrice/maxPrice, tính giá bán động theo quy tắc đang bật trước khi cắt trang chính xác.
        List<ProductResponse> matched = productRepository.findAll(spec, pageable.getSort()).stream()
                .map(p -> dynamicPricingService.toEnrichedProductResponse(p, productMapper, activeRules))
                .filter(p -> matchesPriceRange(p, request.minPrice(), request.maxPrice()))
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

    // ==============
    // ====HEPPER========
    // ==============
    /**
     * Kiểm tra quyền sở hữu sản phẩm: chỉ seller tạo ra sản phẩm hoặc Admin mới được phép thao tác.
     */
    private void verifyProductOwnership(Product product) {
        UUID currentUserId = getCurrentUserId();
        if (currentUserId == null || (!product.getSellerId().equals(currentUserId) && !isCurrentUserAdmin())) {
            throw new AccessDeniedException("You are not allowed to modify this product");
        }
    }

    /**
     * Lấy UUID của user hiện tại từ JWT authentication token.
     */
    private UUID getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null) {
            try {
                return UUID.fromString(auth.getName());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Kiểm tra user hiện tại có quyền Admin không (hỗ trợ cả ROLE_ADMIN và ADMIN privilege).
     */
    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                            || a.getAuthority().equals("ADMIN"));
    }

}
