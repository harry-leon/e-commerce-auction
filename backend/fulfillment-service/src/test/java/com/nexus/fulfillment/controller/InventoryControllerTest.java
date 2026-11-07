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
import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.OrderFailureEventRequest;
import com.nexus.fulfillment.dto.request.OrderPaidEventRequest;
import com.nexus.fulfillment.dto.request.ReservationItemRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
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
import java.time.temporal.ChronoUnit;
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
 * Kiểm thử tầng Web REST Controller cho Quản lý tồn kho và Khóa giữ hàng Checkout/Đấu giá (InventoryController).
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
 * 9. POST /api/v1/inventory/auctions/{id}/reserve: Khóa giữ kho cho phiên đấu giá (HTTP 201 Created).
 * 10. POST /api/v1/inventory/auctions/{id}/extend: Gia hạn giữ kho khi có Anti-sniping (+5 phút).
 * 11. POST /api/v1/inventory/auctions/{id}/release: Giải phóng tồn kho đấu giá khi phiên bị hủy/thất bại.
 * 12. POST /api/v1/inventory/auctions/{id}/transition-to-order: Chuyển tiếp giữ kho sang Đơn hàng của người thắng.
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
                        .with(commerceIdentity())
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
                        .with(commerceIdentity())
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
                        .with(commerceIdentity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/orders/{orderId}/commit - Xác nhận trừ kho thành công theo mã đơn hàng")
    void testCommitByOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(1800);

        ReservationItemResponse item = new ReservationItemResponse(
                reservationId,
                skuId,
                warehouseId,
                2,
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

        when(inventoryService.commitByOrder(orderId)).thenReturn(res);

        mockMvc.perform(post("/api/v1/inventory/orders/" + orderId + "/commit")
                        .with(commerceIdentity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.refId").value(orderId.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/orders/{orderId}/release - Giải phóng hàng giữ kho thành công theo mã đơn")
    void testReleaseByOrder_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseByOrder(eq(orderId), any());

        mockMvc.perform(post("/api/v1/inventory/orders/" + orderId + "/release")
                        .with(commerceIdentity())
                        .param("reason", "ORDER_CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/events/order-paid - Tiếp nhận sự kiện OrderPaid thành công")
    void testHandleOrderPaidEvent_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
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

        when(inventoryService.commitByOrder(orderId)).thenReturn(res);

        OrderPaidEventRequest req = new OrderPaidEventRequest(orderId, null, UUID.randomUUID());

        mockMvc.perform(post("/api/v1/inventory/events/order-paid")
                        .with(commerceIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/events/order-payment-failed - Tiếp nhận sự kiện OrderPaymentFailed thành công")
    void testHandleOrderPaymentFailedEvent_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseByOrder(eq(orderId), any());

        OrderFailureEventRequest req = new OrderFailureEventRequest(orderId, null, "INSUFFICIENT_FUNDS");

        mockMvc.perform(post("/api/v1/inventory/events/order-payment-failed")
                        .with(commerceIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/events/order-cancelled - Tiếp nhận sự kiện OrderCancelled thành công")
    void testHandleOrderCancelledEvent_Success() throws Exception {
        UUID orderId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseByOrder(eq(orderId), any());

        OrderFailureEventRequest req = new OrderFailureEventRequest(orderId, null, "BUYER_REQUESTED");

        mockMvc.perform(post("/api/v1/inventory/events/order-cancelled")
                        .with(commerceIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/reservations/{id}/release - Hoàn trả phiếu giữ kho thành công")
    void testReleaseReservation_Success() throws Exception {
        UUID reservationId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseReservation(eq(reservationId), any());

        mockMvc.perform(post("/api/v1/inventory/reservations/" + reservationId + "/release")
                        .with(commerceIdentity())
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

    @Test
    @DisplayName("POST /api/v1/inventory/auctions/{id}/reserve - Khóa giữ kho cho phiên đấu giá thành công (HTTP 201 Created)")
    void testReserveAuctionStock_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant startTime = Instant.now().plus(1, ChronoUnit.HOURS);
        Instant endTime = startTime.plus(24, ChronoUnit.HOURS);

        ReservationItemResponse item = new ReservationItemResponse(
                UUID.randomUUID(),
                skuId,
                warehouseId,
                1,
                ReservationStatus.PENDING,
                endTime
        );
        ReservationResponse res = new ReservationResponse(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                List.of(item),
                endTime
        );

        when(inventoryService.reserveAuctionStock(eq(auctionId), any(ReserveAuctionInventoryRequest.class)))
                .thenReturn(res);

        ReserveAuctionInventoryRequest req = new ReserveAuctionInventoryRequest(
                skuId,
                warehouseId,
                startTime,
                endTime,
                1
        );

        mockMvc.perform(post("/api/v1/inventory/auctions/" + auctionId + "/reserve")
                        .with(auctionIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.refType").value("AUCTION"))
                .andExpect(jsonPath("$.data.refId").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/auctions/{id}/extend - Gia hạn giữ kho khi có Anti-sniping thành công (HTTP 200 OK)")
    void testExtendAuctionReservation_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant newEndTime = Instant.now().plus(2, ChronoUnit.HOURS);

        ReservationItemResponse item = new ReservationItemResponse(
                UUID.randomUUID(),
                skuId,
                warehouseId,
                1,
                ReservationStatus.PENDING,
                newEndTime
        );
        ReservationResponse res = new ReservationResponse(
                ReservationRefType.AUCTION,
                auctionId,
                ReservationStatus.PENDING,
                List.of(item),
                newEndTime
        );

        when(inventoryService.extendAuctionReservation(eq(auctionId), any(ExtendAuctionReservationRequest.class)))
                .thenReturn(res);

        ExtendAuctionReservationRequest req = new ExtendAuctionReservationRequest(5, newEndTime);

        mockMvc.perform(post("/api/v1/inventory/auctions/" + auctionId + "/extend")
                        .with(auctionIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.refType").value("AUCTION"))
                .andExpect(jsonPath("$.data.refId").value(auctionId.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/auctions/{id}/release - Giải phóng kho đấu giá thành công (HTTP 200 OK)")
    void testReleaseByAuction_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        doNothing().when(inventoryService).releaseByAuction(eq(auctionId), any());

        mockMvc.perform(post("/api/v1/inventory/auctions/" + auctionId + "/release")
                        .with(auctionIdentity())
                        .param("reason", "AUCTION_CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/inventory/auctions/{id}/transition-to-order - Chuyển tiếp giữ kho sang Đơn hàng thành công (HTTP 200 OK)")
    void testTransitionAuctionToOrder_Success() throws Exception {
        UUID auctionId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        Instant deadline = Instant.now().plus(24, ChronoUnit.HOURS);

        ReservationItemResponse item = new ReservationItemResponse(
                UUID.randomUUID(),
                skuId,
                warehouseId,
                1,
                ReservationStatus.PENDING,
                deadline
        );
        ReservationResponse res = new ReservationResponse(
                ReservationRefType.ORDER,
                orderId,
                ReservationStatus.PENDING,
                List.of(item),
                deadline
        );

        when(inventoryService.transitionAuctionToOrder(eq(auctionId), any(TransitionAuctionToOrderRequest.class)))
                .thenReturn(res);

        TransitionAuctionToOrderRequest req = new TransitionAuctionToOrderRequest(orderId, 1440, null);

        mockMvc.perform(post("/api/v1/inventory/auctions/" + auctionId + "/transition-to-order")
                        .with(auctionIdentity())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.refType").value("ORDER"))
                .andExpect(jsonPath("$.data.refId").value(orderId.toString()));
    }
    /** Buyer/admin và token sai service bị chặn trước khi service kho thay đổi dữ liệu. */
    @Test
    void rejectsUserAndWrongServiceForInternalMutations() throws Exception {
        String auction = "/api/v1/inventory/auctions/" + UUID.randomUUID() + "/release";
        String order = "/api/v1/inventory/orders/" + UUID.randomUUID() + "/release";
        mockMvc.perform(post(auction).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(auction).with(commerceIdentity())).andExpect(status().isForbidden());
        mockMvc.perform(post(order).with(auctionIdentity())).andExpect(status().isForbidden());
        mockMvc.perform(post(auction).with(jwt().jwt(j -> j.claim("azp", "nexus-web"))
                .authorities(new SimpleGrantedAuthority("INVENTORY.AUCTION"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(order)).andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(inventoryService);
    }

    /** Fixture quyền service: token người dùng không được gọi mutation giữ kho nội bộ. */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor commerceIdentity() {
        return jwt().jwt(j -> j.subject("commerce-service-account").claim("azp", "nexus-commerce-service"))
                .authorities(new SimpleGrantedAuthority("INVENTORY.ORDER"));
    }

    /** Auction chỉ có quyền vòng đời giữ kho đấu giá, không có quyền trừ kho đơn hàng. */
    private org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor auctionIdentity() {
        return jwt().jwt(j -> j.subject("auction-service-account").claim("azp", "nexus-auction-service"))
                .authorities(new SimpleGrantedAuthority("INVENTORY.AUCTION"));
    }
}
