package com.nexus.catalog.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ResourceNotFoundException;
import com.nexus.catalog.entity.Category;
import com.nexus.catalog.entity.Product;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.dto.mapper.ProductMapper;
import com.nexus.catalog.repository.CategoryRepository;
import com.nexus.catalog.repository.ProductImageRepository;
import com.nexus.catalog.repository.ProductRepository;
import com.nexus.catalog.repository.SkuRepository;
import com.nexus.catalog.service.impl.ProductServiceImpl;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import com.nexus.catalog.dto.request.ProductFilterRequest;
import org.springframework.data.jpa.domain.Specification;

class ProductServiceTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    private ProductRepository productRepository;
    private SkuRepository skuRepository;
    private CategoryRepository categoryRepository;
    private ProductImageRepository productImageRepository;
    private ProductService productService;
    private UUID sellerId;
    private Category category;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @BeforeEach
    void init() {
        productRepository = mock(ProductRepository.class);
        skuRepository = mock(SkuRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        productImageRepository = mock(ProductImageRepository.class);
        productService = new ProductServiceImpl(
                productRepository,
                skuRepository,
                categoryRepository,
                productImageRepository,
                ProductMapper.INSTANCE,
                validator);
        sellerId = UUID.randomUUID();
        category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("Electronics");
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(sellerId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SELLER")))
        );
    }

    @AfterEach
    void tearDownEach() {
        SecurityContextHolder.clearContext();
    }

    private SkuRequest sku(String code, String price) {
        return new SkuRequest(
                code, null, new BigDecimal(price), null, SkuStatus.ACTIVE);
    }

    private ProductRequest command(List<SkuRequest> skus,
            List<ImageRequest> images) {
        return new ProductRequest(
                "iPhone 16", "Describe", category.getId(), false, skus, images);
    }

    private ImageRequest image(int i) {
        return new ImageRequest("https://cdn.example.com/" + i + ".jpg", i);
    }

    private Product existingProduct(ProductStatus status) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setSellerId(sellerId);
        product.setCategory(category);
        product.setStatus(status);
        product.setName("Old name");
        return product;
    }

    @Test
    void createPersistsDraftProduct() {
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(skuRepository.existsBySkuCode("SKU-1")).thenReturn(false);
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse product = productService.create(
                sellerId, command(List.of(sku("SKU-1", "10.00")), List.of(image(0))));

        assertEquals(ProductStatus.DRAFT, product.status());
        assertEquals(sellerId, product.sellerId());
        assertEquals("iPhone 16", product.name());
        assertEquals(1, product.skus().size());
        assertEquals(1, product.images().size());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void createWithoutSellerIsRejected() {
        ProductRequest command = command(List.of(sku("SKU-1", "10.00")), List.of());
        assertThrows(BusinessException.class, () -> productService.create(null, command));
        verify(productRepository, never()).save(any());
    }

    @Test
    void createWithBlankNameIsRejected() {
        ProductRequest command = new ProductRequest(
                "  ", null, category.getId(), false, List.of(sku("SKU-1", "10.00")), null);
        assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void createWithoutCategoryIsRejected() {
        ProductRequest command = new ProductRequest(
                "iPhone", null, null, false, List.of(sku("SKU-1", "10.00")), null);
        assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void createWithoutSkuIsRejected() {
        ProductRequest command = command(List.of(), List.of());
        assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void createWithSixImagesIsRejected() {
        List<ImageRequest> images =
                List.of(image(0), image(1), image(2), image(3), image(4), image(5));
        ProductRequest command = command(List.of(sku("SKU-1", "10.00")), images);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
        assertTrue(ex.getMessage().contains("at most 5 images"));
    }

    @Test
    void createWithFiveImagesIsAccepted() {
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(skuRepository.existsBySkuCode("SKU-1")).thenReturn(false);
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        List<ImageRequest> images =
                List.of(image(0), image(1), image(2), image(3), image(4));

        ProductResponse product = productService.create(
                sellerId, command(List.of(sku("SKU-1", "10.00")), images));

        assertEquals(5, product.images().size());
    }

    @Test
    void createWithMissingPriceIsRejected() {
        SkuRequest noPrice = new SkuRequest(
                "SKU-1", null, null, null, null);
        ProductRequest command = command(List.of(noPrice), null);
        assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void createWithOriginalPriceBelowPriceIsRejected() {
        SkuRequest bad = new SkuRequest(
                "SKU-1", null, new BigDecimal("10.00"), new BigDecimal("5.00"), null);
        ProductRequest command = command(List.of(bad), null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
        assertTrue(ex.getMessage().contains("Original price"));
    }

    @Test
    void createWithDuplicateSkuCodesInCommandIsRejected() {
        ProductRequest command = command(
                List.of(sku("SKU-1", "10.00"), sku("sku-1", "20.00")), null);
        assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void createWithSkuCodeAlreadyInUseIsRejected() {
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.of(category));
        when(skuRepository.existsBySkuCode("SKU-1")).thenReturn(true);
        ProductRequest command = command(List.of(sku("SKU-1", "10.00")), null);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.create(sellerId, command));
        assertTrue(ex.getMessage().contains("already exists"));
        verify(productRepository, never()).save(any());
    }

    @Test
    void createWithUnknownCategoryIsRejected() {
        when(categoryRepository.findById(category.getId())).thenReturn(Optional.empty());
        ProductRequest command = command(List.of(sku("SKU-1", "10.00")), null);
        assertThrows(ResourceNotFoundException.class,
                () -> productService.create(sellerId, command));
    }

    @Test
    void getByIdReturnsProduct() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        ProductResponse response = productService.getById(product.getId());
        assertEquals(product.getId(), response.id());
        assertEquals(ProductStatus.DRAFT, response.status());
    }

    @Test
    void getByIdThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(productRepository.findById(id)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> productService.getById(id));
    }

    @Test
    void listDelegatesToRepositoryWithStatus() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<Product> page = new PageImpl<>(List.of(), pageable, 0);
        when(productRepository.findByStatus(ProductStatus.ACTIVE, pageable))
                .thenReturn(page);
        Page<ProductResponse> result = productService.list(ProductStatus.ACTIVE, pageable);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void listWithoutStatusReturnsAll() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<Product> page = new PageImpl<>(List.of(), pageable, 0);
        when(productRepository.findAll(pageable)).thenReturn(page);
        Page<ProductResponse> result = productService.list(null, pageable);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void deleteRemovesProduct() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        productService.delete(product.getId());
        verify(productRepository).delete(product);
    }

    @Test
    void changeStatusToActiveIsRejectedWithoutApproval() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> productService.changeStatus(product.getId(), ProductStatus.ACTIVE));
        assertTrue(ex.getMessage().contains("approved"));
        assertEquals(ProductStatus.DRAFT, product.getStatus());
        verify(productRepository, never()).save(any());
    }

    @Test
    void changeStatusDraftToInactiveIsAllowed() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse updated =
                productService.changeStatus(product.getId(), ProductStatus.INACTIVE);

        assertEquals(ProductStatus.INACTIVE, updated.status());
    }

    @Test
    void changeStatusInactiveToDraftIsAllowed() {
        Product product = existingProduct(ProductStatus.INACTIVE);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse updated =
                productService.changeStatus(product.getId(), ProductStatus.DRAFT);

        assertEquals(ProductStatus.DRAFT, updated.status());
    }

    @Test
    void changeStatusActiveToInactiveIsAllowed() {
        Product product = existingProduct(ProductStatus.ACTIVE);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse updated =
                productService.changeStatus(product.getId(), ProductStatus.INACTIVE);

        assertEquals(ProductStatus.INACTIVE, updated.status());
    }

    @Test
    void changeStatusWithNullTargetIsRejected() {
        assertThrows(BusinessException.class,
                () -> productService.changeStatus(UUID.randomUUID(), null));
    }

    @Test
    void changeStatusInvalidTransitionIsRejected() {
        Product product = existingProduct(ProductStatus.INACTIVE);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThrows(BusinessException.class,
                () -> productService.changeStatus(product.getId(), ProductStatus.INACTIVE));
    }

    @Test
    void approvePublishesProduct() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse approved = productService.approve(product.getId());

        assertEquals(ProductStatus.ACTIVE, approved.status());
    }

    @Test
    void approveRejectsAlreadyActiveProduct() {
        Product product = existingProduct(ProductStatus.ACTIVE);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        assertThrows(BusinessException.class, () -> productService.approve(product.getId()));
        verify(productRepository, never()).save(any());
    }

    @Test
    void updateKeepsExistingSkuCodeWithoutDuplicateCheck() {
        Product product = existingProduct(ProductStatus.DRAFT);
        com.nexus.catalog.entity.Sku existing = new com.nexus.catalog.entity.Sku();
        existing.setSkuCode("SKU-1");
        existing.setPrice(new BigDecimal("10.00"));
        product.getSkus().add(existing);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse updated = productService.update(
                product.getId(),
                command(List.of(sku("SKU-1", "15.00")), null));

        assertEquals("iPhone 16", updated.name());
        assertEquals(1, updated.skus().size());
        assertEquals(new BigDecimal("15.00"), updated.skus().get(0).price());
        verify(skuRepository, never()).existsBySkuCode(any());
    }

    @Test
    void updateReplacesImagesAndKeepsWithinLimit() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        ProductResponse updated = productService.update(
                product.getId(),
                command(List.of(sku("SKU-1", "10.00")), List.of(image(0), image(1))));

        assertEquals(2, updated.images().size());
    }

    @Test
    void updateThrowsWhenUserIsNotOwner() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SELLER")))
        );

        assertThrows(AccessDeniedException.class,
                () -> productService.update(product.getId(), command(List.of(sku("SKU-1", "15.00")), null)));
    }

    @Test
    void addSkuThrowsWhenUserIsNotOwner() {
        Product product = existingProduct(ProductStatus.DRAFT);
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SELLER")))
        );

        assertThrows(AccessDeniedException.class,
                () -> productService.addSku(product.getId(), sku("SKU-NEW", "20.00")));
    }

    @Test
    void deleteSkuThrowsWhenUserIsNotOwner() {
        Product product = existingProduct(ProductStatus.DRAFT);
        com.nexus.catalog.entity.Sku sku = new com.nexus.catalog.entity.Sku();
        sku.setId(UUID.randomUUID());
        sku.setProduct(product);
        when(skuRepository.findById(sku.getId())).thenReturn(Optional.of(sku));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_SELLER")))
        );

        assertThrows(AccessDeniedException.class,
                () -> productService.deleteSku(sku.getId()));
    }

    // --- Search & Filter Tests ---
    @Test
    void search_ValidCriteria_Success() {
        // Arrange
        ProductFilterRequest filter = new ProductFilterRequest(
            "laptop", category.getId(), new BigDecimal("100"), new BigDecimal("500"), ProductStatus.ACTIVE
        );
        PageRequest pageable = PageRequest.of(0, 10);
        Product product = new Product();
        product.setName("Laptop Gaming");
        product.setCategory(category);
        product.setStatus(ProductStatus.ACTIVE);
        when(productRepository.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of(product), pageable, 1));
        // Act
        Page<ProductResponse> result = productService.search(filter, pageable);
        // Assert
        assertEquals(1, result.getTotalElements());
        assertEquals("Laptop Gaming", result.getContent().get(0).name());
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }
    @Test
    void search_MinPriceGreaterThanMaxPrice_ThrowsBusinessException() {
        // Arrange
        ProductFilterRequest invalidFilter = new ProductFilterRequest(
            null, null, new BigDecimal("500"), new BigDecimal("100"), null
        );
        PageRequest pageable = PageRequest.of(0, 10);
        // Act & Assert
        BusinessException exception = assertThrows(
            BusinessException.class,
            () -> productService.search(invalidFilter, pageable)
        );
        assertEquals("minPrice cannot be greater than maxPrice", exception.getMessage());
        verify(productRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }
    @Test
    void search_NullCriteria_ReturnsDefaultActiveProducts() {
        // Arrange
        PageRequest pageable = PageRequest.of(0, 10);
        when(productRepository.findAll(any(Specification.class), eq(pageable)))
            .thenReturn(Page.empty(pageable));
        // Act
        Page<ProductResponse> result = productService.search(null, pageable);
        // Assert
        assertTrue(result.isEmpty());
        verify(productRepository).findAll(any(Specification.class), eq(pageable));
    }
}



