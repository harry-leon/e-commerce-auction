package com.nexus.commerce.controller.internal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.request.AttachOrderReservationsRequest;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.service.OrderService;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử WebMvc cho endpoint nội bộ AuctionOrderInternalController (DoD KH-S2-2 / SRS §3.5.3).
 *
 * Chức năng:
 * - Xác thực endpoint POST /api/v1/internal/commerce/orders/from-auction.
 * - Kiểm tra phân quyền microservice qua ServiceIdentity (chỉ cho phép client nexus-auction-service kèm quyền COMMERCE.AUCTION).
 * - Kiểm tra validation bean constraints và phản hồi chuẩn ApiResponse HTTP 201 Created.
 */
@WebMvcTest(AuctionOrderInternalController.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
// Một profile test duy nhất để tắt scheduler; ActiveProfiles không cho phép khai báo lặp.
@DisplayName("AuctionOrderInternalController Tests - API nội bộ tạo đơn hàng đấu giá")
class AuctionOrderInternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private final UUID buyerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000088");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final BigDecimal finalPrice = new BigDecimal("2500000.00");
    private final Instant paymentDeadline = Instant.now().plus(24, ChronoUnit.HOURS);

    @Test
    @DisplayName("Tạo đơn hàng thành công khi gọi với ServiceIdentity hợp lệ trả về 201 Created")
    void test_createAuctionOrder_authorized_returns201() throws Exception {
        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId,
                buyerId,
                sellerId,
                skuId,
                finalPrice,
                paymentDeadline
        );

        AuctionOrderResponse response = new AuctionOrderResponse(
                orderId,
                "ORD-AUC-20261109-001",
                auctionId,
                buyerId,
                sellerId,
                OrderStatus.PENDING_PAYMENT,
                finalPrice,
                paymentDeadline
        );

        when(orderService.createAuctionOrder(any(CreateAuctionOrderRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/internal/commerce/orders/from-auction")
                        .with(jwt()
                                .jwt(j -> j.claim("azp", "nexus-auction-service"))
                                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(orderId.toString()))
                .andExpect(jsonPath("$.data.auctionId").value(auctionId.toString()))
                .andExpect(jsonPath("$.data.buyerId").value(buyerId.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));

        verify(orderService).createAuctionOrder(any(CreateAuctionOrderRequest.class));
    }

    @Test
    @DisplayName("Từ chối 403 Forbidden khi client azp không phải nexus-auction-service")
    void test_createAuctionOrder_invalidClient_returns403() throws Exception {
        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId,
                buyerId,
                sellerId,
                skuId,
                finalPrice,
                paymentDeadline
        );

        mockMvc.perform(post("/api/v1/internal/commerce/orders/from-auction")
                        .with(jwt()
                                .jwt(j -> j.claim("azp", "nexus-other-service"))
                                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Từ chối 403 Forbidden khi thiếu quyền COMMERCE.AUCTION")
    void test_createAuctionOrder_missingPrivilege_returns403() throws Exception {
        CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                auctionId,
                buyerId,
                sellerId,
                skuId,
                finalPrice,
                paymentDeadline
        );

        mockMvc.perform(post("/api/v1/internal/commerce/orders/from-auction")
                        .with(jwt()
                                .jwt(j -> j.claim("azp", "nexus-auction-service"))
                                .authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Gắn reservationIds thành công với ServiceIdentity hợp lệ trả về 200 OK")
    void test_attachReservations_authorized_returns200() throws Exception {
        AttachOrderReservationsRequest request = new AttachOrderReservationsRequest(
                java.util.List.of(UUID.randomUUID())
        );

        mockMvc.perform(post("/api/v1/internal/commerce/orders/" + orderId + "/reservations")
                        .with(jwt()
                                .jwt(j -> j.claim("azp", "nexus-auction-service"))
                                .authorities(new SimpleGrantedAuthority("COMMERCE.AUCTION")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(orderService).attachReservations(org.mockito.ArgumentMatchers.eq(orderId), any());
    }
}
