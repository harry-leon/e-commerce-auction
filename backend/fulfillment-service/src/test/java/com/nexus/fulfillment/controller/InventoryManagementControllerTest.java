package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.dto.request.BatchStockRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryReconciliationResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử các endpoint quản trị tồn kho mới/mở rộng trên InventoryController:
 * tra cứu tồn phân trang theo SKU/kho, tra cứu theo skuCode, batch read, nhập kho/điều chỉnh kèm Idempotency-Key,
 * lọc sổ cái bất biến, đối soát tồn kho và ma trận phân quyền ADMIN / SUPPORT / SELLER / BUYER.
 */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class InventoryManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InventoryService inventoryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("GET /api/v1/inventory chỉ cho SELLER/SUPPORT/ADMIN; BUYER bị 403; chưa đăng nhập bị 401")
    void listInventory_EnforcesRbac() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        InventoryStockResponse item = new InventoryStockResponse(skuId, whId, "WH_001", "Kho TP.HCM", "SKU-1", 30, 25, 5, 0);
        when(inventoryService.listInventory(eq(skuId), eq("SKU-1"), eq(whId), eq(null), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/inventory").with(roleJwt("ROLE_BUYER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/inventory")
                        .param("skuId", skuId.toString())
                        .param("skuCode", "SKU-1")
                        .param("warehouseId", whId.toString())
                        .with(roleJwt("ROLE_SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].warehouseCode").value("WH_001"))
                .andExpect(jsonPath("$.data[0].availableQty").value(25));
    }

    @Test
    @DisplayName("GET /sku/code/{skuCode} và POST /stocks/batch cho phép truy cập công khai và validate input")
    void publicReadByCodeAndBatchStock() throws Exception {
        UUID sku1 = UUID.randomUUID();
        UUID sku2 = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        InventoryStockResponse s1 = new InventoryStockResponse(sku1, whId, "WH_001", "Kho TP.HCM", "IP15-BLK", 10, 8, 2, 0);
        InventoryStockResponse s2 = new InventoryStockResponse(sku2, whId, "WH_001", "Kho TP.HCM", null, 0, 0, 0, 0);

        when(inventoryService.getStockBySkuCode("IP15-BLK", whId)).thenReturn(s1);
        when(inventoryService.getBatchStock(any(BatchStockRequest.class))).thenReturn(List.of(s1, s2));

        mockMvc.perform(get("/api/v1/inventory/sku/code/IP15-BLK")
                        .param("warehouseId", whId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skuId").value(sku1.toString()))
                .andExpect(jsonPath("$.data.availableQty").value(8));

        mockMvc.perform(post("/api/v1/inventory/stocks/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStockRequest(List.of(sku1, sku2), whId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        // Danh sách skuIds rỗng -> 422 VALIDATION_ERROR
        mockMvc.perform(post("/api/v1/inventory/stocks/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchStockRequest(List.of(), whId))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("POST /inbound và /adjust truyền Idempotency-Key header, cho ADMIN/SELLER, chặn SUPPORT/BUYER")
    void inboundAndAdjust_EnforcesRbacIdempotencyAndValidation() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        InventoryStockResponse stock = new InventoryStockResponse(skuId, whId, "WH_001", "Kho TP.HCM", "SKU-1", 40, 40, 0, 0);

        when(inventoryService.inboundStock(any(StockInboundRequest.class), eq("inb-idem-1"))).thenReturn(stock);
        when(inventoryService.adjustStock(any(StockAdjustmentRequest.class), eq("adj-idem-1"))).thenReturn(stock);

        StockInboundRequest validInbound = new StockInboundRequest(null, "SKU-1", whId, 20, null, "Nhap dau ky", null);
        StockAdjustmentRequest validAdjust = new StockAdjustmentRequest(skuId, null, whId, null, 40, null, "Kiem ke", null, null);

        // Support chỉ có quyền đọc, không được nhập/điều chỉnh kho -> 403
        mockMvc.perform(post("/api/v1/inventory/inbound")
                        .with(roleJwt("ROLE_SUPPORT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validInbound)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/inventory/adjust")
                        .with(roleJwt("ROLE_BUYER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAdjust)))
                .andExpect(status().isForbidden());

        // Seller nhập kho kèm Idempotency-Key -> 200
        mockMvc.perform(post("/api/v1/inventory/inbound")
                        .with(roleJwt("ROLE_SELLER"))
                        .header("Idempotency-Key", "inb-idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validInbound)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQty").value(40));

        // Admin điều chỉnh kho kèm Idempotency-Key -> 200
        mockMvc.perform(post("/api/v1/inventory/adjust")
                        .with(roleJwt("ROLE_ADMIN"))
                        .header("Idempotency-Key", "adj-idem-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validAdjust)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQty").value(40));

        // Validation 422 khi thiếu cả skuId & skuCode khi nhập kho
        StockInboundRequest invalidInbound = new StockInboundRequest(null, "", whId, 5, null, null, null);
        mockMvc.perform(post("/api/v1/inventory/inbound")
                        .with(roleJwt("ROLE_SELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidInbound)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(inventoryService).inboundStock(any(StockInboundRequest.class), eq("inb-idem-1"));
        verify(inventoryService).adjustStock(any(StockAdjustmentRequest.class), eq("adj-idem-1"));
    }

    @Test
    @DisplayName("GET /ledger và GET /reconcile truyền đủ bộ lọc và giới hạn quyền Seller/Support/Admin")
    void ledgerAndReconcile_FiltersAndRbac() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        UUID refId = UUID.randomUUID();
        Instant from = Instant.parse("2026-11-01T00:00:00Z");
        Instant to = Instant.parse("2026-11-11T23:59:59Z");

        InventoryLedgerResponse ledgerItem = new InventoryLedgerResponse(
                UUID.randomUUID(), skuId, whId, "WH_001", LedgerMovementType.INBOUND,
                15, "INBOUND", refId, "Nhap lo A", UUID.randomUUID(), Instant.now()
        );
        when(inventoryService.getLedger(
                eq(skuId), eq("SKU-1"), eq(whId), eq(LedgerMovementType.INBOUND),
                eq("INBOUND"), eq(refId), eq(from), eq(to), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ledgerItem), PageRequest.of(0, 20), 1));

        InventoryReconciliationResponse rec = new InventoryReconciliationResponse(
                skuId, whId, "WH_001", 15, 15, 0, 0, 15, 0, 0, true, true
        );
        when(inventoryService.reconcileStock(skuId, whId)).thenReturn(List.of(rec));

        mockMvc.perform(get("/api/v1/inventory/ledger").with(roleJwt("ROLE_BUYER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/inventory/reconcile").with(roleJwt("ROLE_BUYER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/inventory/ledger")
                        .param("skuId", skuId.toString())
                        .param("skuCode", "SKU-1")
                        .param("warehouseId", whId.toString())
                        .param("movementType", "INBOUND")
                        .param("refType", "INBOUND")
                        .param("refId", refId.toString())
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .with(roleJwt("ROLE_SELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].warehouseCode").value("WH_001"))
                .andExpect(jsonPath("$.data[0].reason").value("Nhap lo A"));

        mockMvc.perform(get("/api/v1/inventory/reconcile")
                        .param("skuId", skuId.toString())
                        .param("warehouseId", whId.toString())
                        .with(roleJwt("ROLE_SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].matched").value(true))
                .andExpect(jsonPath("$.data[0].nonNegative").value(true));
    }

    private JwtRequestPostProcessor roleJwt(String authority) {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()))
                .authorities(new SimpleGrantedAuthority(authority));
    }
}
