package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationItemResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử tầng Web REST Controller cho Quản lý tồn kho và Khóa giữ hàng Checkout (InventoryController).
 *
 * Tình huống kiểm thử:
 * 1. GET /api/v1/inventory/sku/{skuId}: Công khai không cần token, trả về đúng phong bì ApiResponse chuẩn.
 * 2. POST /api/v1/inventory/check: Kiểm tra tồn hàng loạt cho Cart.
 * 3. POST /api/v1/inventory/reserve: Khóa giữ hàng Atomic Reservation thành công (HTTP 201 Created).
 * 4. POST /api/v1/inventory/reserve: Bán vượt tồn kho bị từ chối với HTTP 409 Conflict (INVENTORY_INSUFFICIENT).
 * 5. POST /api/v1/inventory/reservations/{id}/confirm: Xác nhận xuất kho thành công.
 * 6. POST /api/v1/inventory/reservations/{id}/release: Hoàn trả giữ kho.
 * 7. POST /api/v1/inventory/inbound: Nhập kho (yêu cầu quyền ADMIN hoặc SELLER).
 * 8. GET /api/v1/inventory/ledger: Đọc sổ cái bất biến (yêu cầu quyền ADMIN hoặc SUPPORT).
 */
@WebMvcTest(InventoryController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    @DisplayName("GET /api/v1/inventory/sku/{skuId} - Truy cập công khai, trả về 4 trạng thái tồn kho chuẩn ApiResponse v2")
    void testGetStockBySku_Public_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        InventoryStockResponse stock = new InventoryStockResponse(skuId, warehouseId, "WH_001", 100, 70, 20, 10);

        when(inventoryService.getStockBySku(eq(skuId), any())).thenReturn(stock);

        mockMvc.perform(get("/api/v1/inventory/sku/" + skuId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data.totalQty").value(100))
                .andExpect(jsonPath("$.data.availableQty").value(70))
                .andExpect(jsonPath("$.data.reservedQty").value(20))
                .andExpect(jsonPath("$.data.unavailableQty").value(10))
                .andExpect(jsonPath("$.meta.requestId").exists())
                .andExpect(jsonPath("$.meta.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/v1/inventory/check - Kiểm tra tồn hàng loạt cho Cart công khai")
    void testCheckAvailability_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        SkuAvailabilityResponse item = new SkuAvailabilityResponse(skuId, warehouseId, 50, true);

        when(inventoryService.checkAvailability(any())).thenReturn(List.of(item));

        SkuAvailabilityCheckRequest req = new SkuAvailabilityCheckRequest(List.of(skuId), warehouseId);

        mockMvc.perform(post("/api/v1/inventory/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data[0].availableQty").value(50))
                .andExpect(jsonPath("$.data[0].inStock").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/reserve - Yêu cầu giữ kho khi Checkout cần xác thực JWT, trả HTTP 201 Created")
    void testReserveStock_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(1800);

        ReservationItemResponse item = new ReservationItemResponse(
                reservationId,
                skuId,
                warehouseId,
                2,
                ReservationStatus.PENDING,
                expiresAt
        );
        ReservationResponse res = new ReservationResponse(
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                List.of(item),
                expiresAt
        );

        when(inventoryService.reserveStock(any())).thenReturn(res);

        CreateReservationRequest req = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 2, warehouseId)),
                30
        );

        mockMvc.perform(post("/api/v1/inventory/reserve")
                        .with(jwt().jwt(j -> j.subject("buyer-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/reserve - Chặn bán vượt tồn (ALLOW_OVERSELL = false), trả HTTP 409 Conflict")
    void testReserveStock_InsufficientStock_ReturnsConflict() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        when(inventoryService.reserveStock(any())).thenThrow(new BusinessException(
                ErrorCode.INVENTORY_INSUFFICIENT,
                "Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua"
        ));

        CreateReservationRequest req = new CreateReservationRequest(
                ReservationRefType.ORDER,
                orderId,
                List.of(new ReservationItemRequest(skuId, 100, null)),
                30
        );

        mockMvc.perform(post("/api/v1/inventory/reserve")
                        .with(jwt().jwt(j -> j.subject("buyer-123")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVENTORY_INSUFFICIENT"))
                .andExpect(jsonPath("$.error.message").value("Số lượng tồn kho khả dụng không đủ đáp ứng yêu cầu đặt mua"));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/reservations/{id}/confirm - Xác nhận xuất kho thành công")
    void testConfirmReservation_Success() throws Exception {
        UUID reservationId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(1800);

        ReservationItemResponse item = new ReservationItemResponse(
                reservationId,
                skuId,
                warehouseId,
                1,
                ReservationStatus.CONFIRMED,
                expiresAt
        );
        ReservationResponse res = new ReservationResponse(
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.CONFIRMED,
                List.of(item),
                expiresAt
        );

        when(inventoryService.confirmReservation(reservationId)).thenReturn(res);

        mockMvc.perform(post("/api/v1/inventory/reservations/" + reservationId + "/confirm")
                        .with(jwt().jwt(j -> j.subject("service-caller"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/reservations/{id}/release - Hoàn trả phiếu giữ kho thành công")
    void testReleaseReservation_Success() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseReservation(eq(reservationId), any());

        mockMvc.perform(post("/api/v1/inventory/reservations/" + reservationId + "/release")
                        .with(jwt().jwt(j -> j.subject("buyer-123")))
                        .param("reason", "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/inbound - Nhập kho yêu cầu quyền ADMIN hoặc SELLER")
    void testInboundStock_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        InventoryStockResponse stock = new InventoryStockResponse(skuId, warehouseId, "WH_001", 50, 50, 0, 0);

        when(inventoryService.inboundStock(any())).thenReturn(stock);

        StockInboundRequest req = new StockInboundRequest(skuId, warehouseId, 50, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/inventory/inbound")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalQty").value(50));
    }

    @Test
    @DisplayName("GET /api/v1/inventory/ledger - Đọc Sổ cái tồn kho bất biến có phân trang, yêu cầu quyền SUPPORT/ADMIN")
    void testGetLedger_Success() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        InventoryLedgerResponse item = new InventoryLedgerResponse(
                UUID.randomUUID(),
                skuId,
                warehouseId,
                LedgerMovementType.INBOUND,
                100,
                "INBOUND",
                null,
                Instant.now()
        );
        Page<InventoryLedgerResponse> page = new PageImpl<>(List.of(item), PageRequest.of(0, 10), 1);

        when(inventoryService.getLedger(any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/inventory/ledger")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].movementType").value("INBOUND"))
                .andExpect(jsonPath("$.data[0].quantity").value(100))
                .andExpect(jsonPath("$.meta.pagination.totalElements").value(1));
    }
}
