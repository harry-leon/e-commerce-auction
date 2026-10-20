package com.nexus.catalog.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.nexus.catalog.dto.request.ProductFilterRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.catalog.config.SecurityConfig;
import com.nexus.catalog.dto.request.ImageRequest;
import com.nexus.catalog.dto.request.ProductRequest;
import com.nexus.catalog.dto.request.SkuRequest;
import com.nexus.catalog.dto.response.ImageResponse;
import com.nexus.catalog.dto.response.ProductResponse;
import com.nexus.catalog.dto.response.SkuResponse;
import com.nexus.catalog.exception.BusinessException;
import com.nexus.catalog.exception.NotFoundException;
import com.nexus.catalog.enums.ProductStatus;
import com.nexus.catalog.enums.SkuStatus;
import com.nexus.catalog.service.ProductService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    // Mock service tìm kiếm riêng để WebMvcTest chỉ kiểm tra routing, phân quyền và định dạng response.
    private com.nexus.catalog.service.ProductSearchService searchService;

    private UUID sellerId;
    private UUID productId;
    private UUID categoryId;
    private UUID skuId;
    private UUID imageId;
    private ProductResponse sampleResponse;

    @BeforeEach
    void setUp() {
        sellerId = UUID.randomUUID();
        productId = UUID.randomUUID();
        categoryId = UUID.randomUUID();
        skuId = UUID.randomUUID();
        imageId = UUID.randomUUID();

        SkuResponse skuResp = new SkuResponse(
                skuId, "SKU-001", "{\"color\":\"red\"}",
                new BigDecimal("100.00"), new BigDecimal("120.00"), SkuStatus.ACTIVE);
        ImageResponse imgResp = new ImageResponse(imageId, "https://example.com/img1.png", 0);

        sampleResponse = new ProductResponse(
                productId, sellerId, categoryId, "Sample Product",
                "Description", ProductStatus.DRAFT, false, Instant.now(),
                List.of(skuResp), List.of(imgResp));
    }

    private SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor withUser(UUID userId, String... authorities) {
        org.springframework.security.core.GrantedAuthority[] authArray = Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toArray(org.springframework.security.core.GrantedAuthority[]::new);
        return jwt().jwt(j -> j.subject(userId.toString())).authorities(authArray);
    }

    private ProductRequest createSampleRequest() {
        SkuRequest skuReq = new SkuRequest(
                "SKU-001", "{\"color\":\"red\"}",
                new BigDecimal("100.00"), new BigDecimal("120.00"), SkuStatus.ACTIVE);
        ImageRequest imgReq = new ImageRequest("https://example.com/img1.png", 0);
        return new ProductRequest(
                "Sample Product", "Description", categoryId,
                false, List.of(skuReq), List.of(imgReq));
    }

    @Test
    void createProduct_Success() throws Exception {
        ProductRequest request = createSampleRequest();
        when(productService.create(eq(sellerId), any(ProductRequest.class)))
                .thenReturn(sampleResponse);

        mockMvc.perform(post("/api/v1/products")
                        .with(withUser(sellerId, "PRODUCT.CREATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(productId.toString()))
                .andExpect(jsonPath("$.data.name").value("Sample Product"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void createProduct_Unauthorized_WhenNoAuth() throws Exception {
        ProductRequest request = createSampleRequest();

        mockMvc.perform(post("/api/v1/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createProduct_Forbidden_WhenMissingPrivilege() throws Exception {
        ProductRequest request = createSampleRequest();

        mockMvc.perform(post("/api/v1/products")
                        .with(withUser(sellerId, "PRODUCT.VIEW"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void createProduct_ValidationError_WhenMissingRequiredFields() throws Exception {
        ProductRequest invalidRequest = new ProductRequest(
                "", null, null, false, List.of(), null);

        mockMvc.perform(post("/api/v1/products")
                        .with(withUser(sellerId, "PRODUCT.CREATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateProduct_Success() throws Exception {
        ProductRequest request = createSampleRequest();
        when(productService.update(eq(productId), any(ProductRequest.class)))
                .thenReturn(sampleResponse);

        mockMvc.perform(put("/api/v1/products/{id}", productId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(productId.toString()));
    }

    @Test
    void deleteProduct_Success() throws Exception {
        doNothing().when(productService).delete(productId);

        mockMvc.perform(delete("/api/v1/products/{id}", productId)
                        .with(withUser(sellerId, "PRODUCT.DELETE")))
                .andExpect(status().isNoContent());
    }

    @Test
    void getProductById_Success() throws Exception {
        when(productService.getById(productId)).thenReturn(sampleResponse);

        mockMvc.perform(get("/api/v1/products/{id}", productId)
                        .with(withUser(sellerId, "PRODUCT.VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(productId.toString()));
    }

    @Test
    void getProductById_NotFound() throws Exception {
        when(productService.getById(productId))
                .thenThrow(new NotFoundException("Product not found: " + productId));

        mockMvc.perform(get("/api/v1/products/{id}", productId)
                        .with(withUser(sellerId, "PRODUCT.VIEW")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listProducts_Success() throws Exception {
        when(searchService.search(any(), any(), any(), any(), any(), eq(ProductStatus.DRAFT), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/products")
                        .param("status", "DRAFT")
                        .with(withUser(sellerId, "PRODUCT.LIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(productId.toString()));
    }

    @Test
    void listBySeller_Success() throws Exception {
        when(productService.listBySeller(eq(sellerId), any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse), PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/api/v1/products/seller/{sellerId}", sellerId)
                        .with(withUser(sellerId, "PRODUCT.LIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].sellerId").value(sellerId.toString()));
    }

    @Test
    void changeStatus_Success_WithAdminRole() throws Exception {
        ProductResponse updatedStatus = new ProductResponse(
                productId, sellerId, categoryId, "Sample Product",
                "Description", ProductStatus.INACTIVE, false, Instant.now(),
                List.of(), List.of());
        when(productService.changeStatus(productId, ProductStatus.INACTIVE))
                .thenReturn(updatedStatus);

        mockMvc.perform(post("/api/v1/products/{id}/status", productId)
                        .param("target", "INACTIVE")
                        .with(withUser(UUID.randomUUID(), "ROLE_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));
    }

    @Test
    void changeStatus_BusinessError_WhenTargetActiveWithoutApproval() throws Exception {
        when(productService.changeStatus(productId, ProductStatus.ACTIVE))
                .thenThrow(new BusinessException("Product must be approved by Admin/Support to become ACTIVE"));

        mockMvc.perform(post("/api/v1/products/{id}/status", productId)
                        .param("target", "ACTIVE")
                        .with(withUser(UUID.randomUUID(), "ROLE_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"))
                .andExpect(jsonPath("$.message").value("Product must be approved by Admin/Support to become ACTIVE"));
    }

    @Test
    void approveProduct_Success_WithSupportRole() throws Exception {
        ProductResponse approvedResponse = new ProductResponse(
                productId, sellerId, categoryId, "Sample Product",
                "Description", ProductStatus.ACTIVE, false, Instant.now(),
                List.of(), List.of());
        when(productService.approve(productId)).thenReturn(approvedResponse);

        mockMvc.perform(post("/api/v1/products/{id}/approve", productId)
                        .with(withUser(UUID.randomUUID(), "ROLE_SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void approveProduct_Forbidden_ForRegularUser() throws Exception {
        mockMvc.perform(post("/api/v1/products/{id}/approve", productId)
                        .with(withUser(sellerId, "PRODUCT.VIEW")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- SKU API Tests ---

    @Test
    void addSku_Success() throws Exception {
        SkuRequest skuReq = new SkuRequest(
                "SKU-002", "{\"color\":\"blue\"}",
                new BigDecimal("150.00"), new BigDecimal("160.00"), SkuStatus.ACTIVE);
        SkuResponse skuResp = new SkuResponse(
                UUID.randomUUID(), "SKU-002", "{\"color\":\"blue\"}",
                new BigDecimal("150.00"), new BigDecimal("160.00"), SkuStatus.ACTIVE);

        when(productService.addSku(eq(productId), any(SkuRequest.class)))
                .thenReturn(skuResp);

        mockMvc.perform(post("/api/v1/products/{productId}/skus", productId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(skuReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.skuCode").value("SKU-002"));
    }

    @Test
    void updateSku_Success() throws Exception {
        SkuRequest skuReq = new SkuRequest(
                "SKU-001-MOD", null,
                new BigDecimal("110.00"), null, SkuStatus.ACTIVE);
        SkuResponse skuResp = new SkuResponse(
                skuId, "SKU-001-MOD", null,
                new BigDecimal("110.00"), null, SkuStatus.ACTIVE);

        when(productService.updateSku(eq(skuId), any(SkuRequest.class)))
                .thenReturn(skuResp);

        mockMvc.perform(put("/api/v1/products/skus/{skuId}", skuId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(skuReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuCode").value("SKU-001-MOD"));
    }

    @Test
    void deleteSku_Success() throws Exception {
        doNothing().when(productService).deleteSku(skuId);

        mockMvc.perform(delete("/api/v1/products/skus/{skuId}", skuId)
                        .with(withUser(sellerId, "PRODUCT.DELETE")))
                .andExpect(status().isNoContent());
    }

    // --- Image API Tests ---

    @Test
    void addImage_Success() throws Exception {
        ImageRequest imgReq = new ImageRequest("https://example.com/img2.png", 1);
        ImageResponse imgResp = new ImageResponse(UUID.randomUUID(), "https://example.com/img2.png", 1);

        when(productService.addImage(eq(productId), any(ImageRequest.class)))
                .thenReturn(imgResp);

        mockMvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(imgReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.url").value("https://example.com/img2.png"));
    }

    @Test
    void addImage_MaxImageLimitExceeded() throws Exception {
        ImageRequest imgReq = new ImageRequest("https://example.com/img6.png", 6);
        when(productService.addImage(eq(productId), any(ImageRequest.class)))
                .thenThrow(new BusinessException("Product may have at most 5 images"));

        mockMvc.perform(post("/api/v1/products/{productId}/images", productId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(imgReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BUSINESS_ERROR"))
                .andExpect(jsonPath("$.message").value("Product may have at most 5 images"));
    }

    @Test
    void updateImage_Success() throws Exception {
        ImageRequest imgReq = new ImageRequest("https://example.com/img_updated.png", 2);
        ImageResponse imgResp = new ImageResponse(imageId, "https://example.com/img_updated.png", 2);

        when(productService.updateImage(eq(imageId), any(ImageRequest.class)))
                .thenReturn(imgResp);

        mockMvc.perform(put("/api/v1/products/images/{imageId}", imageId)
                        .with(withUser(sellerId, "PRODUCT.UPDATE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(imgReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value("https://example.com/img_updated.png"));
    }

    @Test
    void deleteImage_Success() throws Exception {
        doNothing().when(productService).deleteImage(imageId);

        mockMvc.perform(delete("/api/v1/products/images/{imageId}", imageId)
                        .with(withUser(sellerId, "PRODUCT.DELETE")))
                .andExpect(status().isNoContent());
    }
    // --- Search & Filter Endpoint Tests ---
    @Test
    void search_ValidQueryParams_Returns200AndApiResponseList() throws Exception {
        // Arrange
        PageRequest pageable = PageRequest.of(0, 20);
        Page<ProductResponse> page = new PageImpl<>(List.of(sampleResponse), pageable, 1);
        when(productService.search(any(ProductFilterRequest.class), any()))
            .thenReturn(page);
        // Act & Assert
        mockMvc.perform(get("/api/v1/products/search")
                .param("keyword", "test")
                .param("minPrice", "10")
                .param("maxPrice", "100")
                .param("page", "0")
                .param("size", "20")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.data[0].id").value(productId.toString()))
            .andExpect(jsonPath("$.meta.page").value(0))
            .andExpect(jsonPath("$.meta.pageSize").value(20))
            .andExpect(jsonPath("$.meta.total").value(1));
    }
    @Test
    void search_NegativeMinPrice_Returns400BadRequest() throws Exception {
        // Act & Assert: param minPrice âm vi phạm @Min(0)
        mockMvc.perform(get("/api/v1/products/search")
                .param("minPrice", "-10")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
