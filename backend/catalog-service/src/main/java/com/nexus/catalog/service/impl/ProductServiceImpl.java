package com.nexus.catalog.service.impl;

import com.nexus.catalog.constant.ProductConstant;
import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.NotFoundException;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.entity.ProductImage;
import com.nexus.catalog.entity.Sku;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.ProductImageRepository;
import com.nexus.catalog.repository.ProductRepository;
import com.nexus.catalog.repository.SkuRepository;
import com.nexus.catalog.service.ProductService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;


@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final SkuRepository skuRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final ProductMapper productMapper;
    private final Validator validator;

    public ProductServiceImpl(
            ProductRepository productRepository,
            SkuRepository skuRepository,
            CategoryRepository categoryRepository,
            ProductImageRepository productImageRepository,
            ProductMapper productMapper,
            Validator validator) {
        this.productRepository = productRepository;
        this.skuRepository = skuRepository;
        this.categoryRepository = categoryRepository;
        this.productImageRepository = productImageRepository;
        this.productMapper = productMapper != null ? productMapper : ProductMapper.INSTANCE;
        this.validator = validator;
    }

    @Override
    public ProductResponse create(UUID sellerId, ProductRequest request) {
        if (sellerId == null) {
            throw new BusinessException("Seller ID is required");
        }
        validateRequest(request);
        
        Set<String> seenCodes = new HashSet<>();
        for (SkuRequest skuReq : request.skus()) {
            if (!seenCodes.add(skuReq.skuCode().toLowerCase())) {
                throw new BusinessException("Duplicate SKU code in request: " + skuReq.skuCode());
            }
        }
        for (SkuRequest skuReq : request.skus()) {
            validateOriginalPrice(skuReq);
        }
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
        for (SkuRequest skuReq : request.skus()) {
            if (skuRepository.existsBySkuCode(skuReq.skuCode())) {
                throw new BusinessException("SKU code already exists: " + skuReq.skuCode());
            }
        }
        // Save DB
        Product product = productMapper.toEntity(request);
        product.setSellerId(sellerId);
        product.setCategory(category);
        product.setSkus(buildSkus(request.skus(), product));
        if (request.images() != null && !request.images().isEmpty()) {
            product.setImages(buildImages(request.images(), product));
        }
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    public ProductResponse update(UUID id, ProductRequest request) {
        // Validate request 
        validateRequest(request);
        Product product = findProductById(id);
        verifyProductOwnership(product);
        Set<String> existingCodes = product.getSkus().stream().map(Sku::getSkuCode).collect(Collectors.toSet());
        
        // check dub
        Set<String> seenCodes = new HashSet<>();
        for (SkuRequest skuReq : request.skus()) {
            if (!seenCodes.add(skuReq.skuCode().toLowerCase())) {
                throw new BusinessException("Duplicate SKU code in request: " + skuReq.skuCode());
            }
        }
        for (SkuRequest skuReq : request.skus()) {
            validateOriginalPrice(skuReq);
            if (!existingCodes.contains(skuReq.skuCode()) && skuRepository.existsBySkuCode(skuReq.skuCode())) {
                throw new BusinessException("SKU code already exists: " + skuReq.skuCode());
            }
        }
        var category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("Category not found: " + request.categoryId()));
        
        // save db
        productMapper.updateProductFromRequest(request, product);
        product.setCategory(category);
        product.getSkus().clear();
        product.getSkus().addAll(buildSkus(request.skus(), product));
        product.getImages().clear();
        if (request.images() != null && !request.images().isEmpty()) {
            product.getImages().addAll(buildImages(request.images(), product));
        }
        return productMapper.toResponse(productRepository.save(product));
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
        return productMapper.toResponse(product);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> list(ProductStatus status, Pageable pageable) {
        if (status != null) {
            return productRepository.findByStatus(status, pageable)
                    .map(productMapper::toResponse);
        }
        return productRepository.findAll(pageable).map(productMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductResponse> listBySeller(UUID sellerId, Pageable pageable) {
        return productRepository.findBySellerId(sellerId, pageable)
                .map(productMapper::toResponse);
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
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    public ProductResponse approve(UUID id) {
        Product product = findProductById(id);
        if (product.getStatus() == ProductStatus.ACTIVE) {
            throw new BusinessException("Product is already ACTIVE");
        }
        product.setStatus(ProductStatus.ACTIVE);
        return productMapper.toResponse(productRepository.save(product));
    }

    @Override
    public SkuResponse addSku(UUID productId, SkuRequest request) {
        validateOriginalPrice(request);
        Product product = findProductById(productId);
        verifyProductOwnership(product);
        if (skuRepository.existsBySkuCode(request.skuCode())) {
            throw new BusinessException("SKU code already exists: " + request.skuCode());
        }
        Sku sku = productMapper.toSkuEntity(request);
        sku.setProduct(product);
        product.getSkus().add(sku);
        productRepository.save(product);
        return productMapper.toSku(sku);
    }

    @Override
    public SkuResponse updateSku(UUID skuId, SkuRequest request) {
        validateOriginalPrice(request);
        Sku sku = skuRepository.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SKU not found: " + skuId));
        verifyProductOwnership(sku.getProduct());
        if (skuRepository.existsBySkuCodeAndIdNot(request.skuCode(), skuId)) {
            throw new BusinessException("SKU code already exists: " + request.skuCode());
        }
        productMapper.updateSkuFromRequest(request, sku);
        return productMapper.toSku(skuRepository.save(sku));
    }

    @Override
    public void deleteSku(UUID skuId) {
        Sku sku = skuRepository.findById(skuId)
                .orElseThrow(() -> new NotFoundException("SKU not found: " + skuId));
        verifyProductOwnership(sku.getProduct());
        skuRepository.delete(sku);
    }

    @Override
    public ImageResponse addImage(UUID productId, ImageRequest request) {
        Product product = findProductById(productId);
        verifyProductOwnership(product);
        if (product.getImages().size() >= ProductConstant.MAX_IMAGES) {
            throw new BusinessException("Product may have at most " + ProductConstant.MAX_IMAGES + " images");
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
                .orElseThrow(() -> new NotFoundException("Image not found: " + imageId));
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
                .orElseThrow(() -> new NotFoundException("Image not found: " + imageId));
        verifyProductOwnership(image.getProduct());
        Product product = image.getProduct();
        product.getImages().remove(image);
        productRepository.save(product);
    }

    private Product findProductById(UUID id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Product not found: " + id));
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

    private void validateOriginalPrice(SkuRequest skuReq) {
        if (skuReq.originalPrice() != null && skuReq.price() != null
                && skuReq.originalPrice().compareTo(skuReq.price()) < 0) {
            throw new BusinessException(
                    "Original price must be >= price for SKU: " + skuReq.skuCode());
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
