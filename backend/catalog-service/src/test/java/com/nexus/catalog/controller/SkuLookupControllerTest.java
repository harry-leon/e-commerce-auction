package com.nexus.catalog.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexus.catalog.config.SecurityConfig;
import com.nexus.catalog.service.SkuLookupService;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử Web API tra cứu SKU Catalog (/api/v1/skus/{id} và alias /api/v1/products/skus/{id}).
 * Đảm bảo trả đúng sellerId/productId cho Fulfillment xác thực quyền sở hữu trước khi nhập kho,
 * không chứa trường skuCode và trả HTTP 404 khi SKU không tồn tại.
 */
@WebMvcTest(SkuLookupController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class SkuLookupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SkuLookupService skuLookupService;

    @Test
    void getSkuById_ReturnsSnapshotWithoutSkuCode() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        var snapshot = new SkuLookupService.SkuSnapshot(
                skuId,
                productId,
                "Laptop Pro",
                "https://cdn.example.com/1.png",
                new BigDecimal("1150000.00"),
                new BigDecimal("1000000.00"),
                null,
                true,
                sellerId);

        when(skuLookupService.get(skuId)).thenReturn(snapshot);

        mockMvc.perform(get("/api/v1/skus/{id}", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data.productId").value(productId.toString()))
                .andExpect(jsonPath("$.data.sellerId").value(sellerId.toString()))
                .andExpect(jsonPath("$.data.originalPrice").value(1000000.00))
                .andExpect(jsonPath("$.data.price").value(1150000.00))
                .andExpect(jsonPath("$.data.skuCode").doesNotExist());

        mockMvc.perform(get("/api/v1/products/skus/{id}", skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(skuId.toString()));
    }

    @Test
    void getSkuById_NotFound_Returns404() throws Exception {
        UUID missingId = UUID.randomUUID();
        when(skuLookupService.get(missingId)).thenThrow(new ResourceNotFoundException("Không tìm thấy SKU"));

        mockMvc.perform(get("/api/v1/skus/{id}", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }
}
