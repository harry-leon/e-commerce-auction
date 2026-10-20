package com.nexus.fulfillment.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.fulfillment.config.SecurityConfig;
import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckItemRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.response.InventoryCheckItemResult;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.ReservationItemResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import com.nexus.fulfillment.exception.InsufficientStockException;
import com.nexus.fulfillment.exception.InventoryErrorCode;
import com.nexus.fulfillment.exception.ReservationExpiredException;
import com.nexus.fulfillment.exception.ReservationNotFoundException;
import com.nexus.fulfillment.exception.SkuNotFoundException;
import com.nexus.fulfillment.service.InventoryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử MockMvc cho InventoryController trong Fulfillment Service.
 *
 * Chức năng:
 * - Kiểm chứng định dạng hợp đồng API (API Contracts & DTOs) cho toàn bộ chu trình tồn kho:
 *   1. Kiểm tra tồn kho khả dụng (/api/v1/inventory/check).
 *   2. Tạo phiếu giữ hàng (/api/v1/inventory/reservations).
 *   3. Tra cứu phiếu giữ hàng (/api/v1/inventory/reservations/{id}).
 *   4. Xác nhận xuất kho (/api/v1/inventory/reservations/{id}/commit).
 *   5. Hủy giữ hàng hoàn kho (/api/v1/inventory/reservations/{id}/release).
 *   6. Tra cứu tồn kho SKU (/api/v1/inventory/sku/{skuId}).
 * - Kiểm chứng bọc ApiResponse<T> chuẩn cho kết quả thành công.
 * - Kiểm chứng mã lỗi HTTP và Business Error Codes:
 *   + ERR_INSUFFICIENT_STOCK (HTTP 409)
 *   + ERR_RESERVATION_EXPIRED (HTTP 410)
 *   + ERR_SKU_NOT_FOUND (HTTP 404)
 *   + ERR_RESERVATION_NOT_FOUND (HTTP 404)
 *   + VALIDATION_ERROR (HTTP 400)
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
    @DisplayName("Kiểm tra tồn kho thành công trả về ApiResponse<InventoryCheckResponse> với HTTP 200")
    void checkStockReturnsSuccess() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        var request = new InventoryCheckRequest(List.of(
                new InventoryCheckItemRequest(skuId, whId, 2)
        ));

        var response = new InventoryCheckResponse(
                true,
                List.of(new InventoryCheckItemResult(skuId, whId, 2, 10, true))
        );

        when(inventoryService.checkStock(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/inventory/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data.items[0].availableQuantity").value(10))
                .andExpect(jsonPath("$.meta.version").value("v1"));
    }

    @Test
    @DisplayName("Kiểm tra tồn kho thất bại do validation lỗi (items rỗng) trả về HTTP 400 VALIDATION_ERROR")
    void checkStockFailsValidation() throws Exception {
        var request = new InventoryCheckRequest(List.of());

        mockMvc.perform(post("/api/v1/inventory/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng thành công trả về ApiResponse<ReservationResponse> với HTTP 201 CREATED")
    void createReservationSuccess() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(1800);

        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                expiresAt,
                List.of(new ReservationItemRequest(skuId, null, 2))
        );

        var response = new ReservationResponse(
                reservationId,
                orderId,
                ReservationRefType.ORDER,
                ReservationStatus.PENDING,
                now,
                expiresAt,
                List.of(new ReservationItemResponse(UUID.randomUUID(), skuId, UUID.randomUUID(), 2))
        );

        when(inventoryService.createReservation(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.meta.version").value("v1"));
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng thất bại do không đủ tồn trả về HTTP 409 ERR_INSUFFICIENT_STOCK")
    void createReservationInsufficientStock() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                null,
                List.of(new ReservationItemRequest(skuId, null, 100))
        );

        when(inventoryService.createReservation(any()))
                .thenThrow(new InsufficientStockException(skuId, 100, 5));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(InventoryErrorCode.ERR_INSUFFICIENT_STOCK))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    @DisplayName("Tạo phiếu giữ hàng thất bại do SKU không tồn tại trả về HTTP 404 ERR_SKU_NOT_FOUND")
    void createReservationSkuNotFound() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        var request = new CreateReservationRequest(
                orderId,
                ReservationRefType.ORDER,
                null,
                List.of(new ReservationItemRequest(skuId, null, 1))
        );

        when(inventoryService.createReservation(any()))
                .thenThrow(new SkuNotFoundException(skuId));

        mockMvc.perform(post("/api/v1/inventory/reservations")
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(InventoryErrorCode.ERR_SKU_NOT_FOUND));
    }

    @Test
    @DisplayName("Tra cứu phiếu giữ hàng thành công trả về ApiResponse<ReservationResponse>")
    void getReservationSuccess() throws Exception {
        UUID resId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var response = new ReservationResponse(
                resId,
                orderId,
                ReservationRefType.ORDER,
                ReservationStatus.PENDING,
                Instant.now(),
                Instant.now().plusSeconds(1800),
                List.of()
        );

        when(inventoryService.getReservation(resId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/inventory/reservations/{id}", resId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.reservationId").value(resId.toString()));
    }

    @Test
    @DisplayName("Tra cứu phiếu giữ hàng không tồn tại trả về HTTP 404 ERR_RESERVATION_NOT_FOUND")
    void getReservationNotFound() throws Exception {
        UUID resId = UUID.randomUUID();
        when(inventoryService.getReservation(resId))
                .thenThrow(new ReservationNotFoundException(resId));

        mockMvc.perform(get("/api/v1/inventory/reservations/{id}", resId)
                        .with(jwt().jwt(j -> j.subject("user-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(InventoryErrorCode.ERR_RESERVATION_NOT_FOUND));
    }

    @Test
    @DisplayName("Commit phiếu giữ hàng thành công trả về trạng thái CONFIRMED")
    void commitReservationSuccess() throws Exception {
        UUID resId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var response = new ReservationResponse(
                resId,
                orderId,
                ReservationRefType.ORDER,
                ReservationStatus.CONFIRMED,
                Instant.now(),
                Instant.now().plusSeconds(1800),
                List.of()
        );

        when(inventoryService.commitReservation(eq(resId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/inventory/reservations/{id}/commit", resId)
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommitReservationRequest("PAYMENT_SUCCESS"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("Commit phiếu giữ hàng đã hết hạn trả về HTTP 410 ERR_RESERVATION_EXPIRED")
    void commitReservationExpired() throws Exception {
        UUID resId = UUID.randomUUID();
        when(inventoryService.commitReservation(eq(resId), any()))
                .thenThrow(new ReservationExpiredException(resId, Instant.now().minusSeconds(60)));

        mockMvc.perform(post("/api/v1/inventory/reservations/{id}/commit", resId)
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CommitReservationRequest("PAYMENT_SUCCESS"))))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value(InventoryErrorCode.ERR_RESERVATION_EXPIRED));
    }

    @Test
    @DisplayName("Release phiếu giữ hàng thành công trả về trạng thái RELEASED")
    void releaseReservationSuccess() throws Exception {
        UUID resId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var response = new ReservationResponse(
                resId,
                orderId,
                ReservationRefType.ORDER,
                ReservationStatus.RELEASED,
                Instant.now(),
                Instant.now().plusSeconds(1800),
                List.of()
        );

        when(inventoryService.releaseReservation(eq(resId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/inventory/reservations/{id}/release", resId)
                        .with(jwt().jwt(j -> j.subject("user-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReleaseReservationRequest("ORDER_CANCELLED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RELEASED"));
    }

    @Test
    @DisplayName("Tra cứu tồn kho SKU trả về ApiResponse<SkuInventoryResponse> với HTTP 200")
    void getSkuInventorySuccess() throws Exception {
        UUID skuId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();
        var response = new SkuInventoryResponse(skuId, whId, 100, 20, 5, 75);

        when(inventoryService.getSkuInventory(skuId, whId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/inventory/sku/{skuId}", skuId)
                        .param("warehouseId", whId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.skuId").value(skuId.toString()))
                .andExpect(jsonPath("$.data.availableQuantity").value(75));
    }
}
