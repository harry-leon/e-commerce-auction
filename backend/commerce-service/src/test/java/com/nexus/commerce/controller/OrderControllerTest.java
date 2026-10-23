package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.request.OrderStatusChangeRequest;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.PageResponse;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiểm thử lớp Web MVC Controller cho OrderController.
 */
@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private final UUID userId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private OrderResponse mockOrderResponse() {
        return new OrderResponse(
                orderId,
                "ORD-20261023-ABCDEF",
                userId,
                UUID.randomUUID(),
                OrderSourceType.REGULAR,
                null,
                null,
                OrderStatus.PENDING_PAYMENT,
                new BigDecimal("500000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("500000.00"),
                "123 Le Loi, Quan 1, TP.HCM",
                "Nguyen Van A",
                "0901234567",
                "idemp-key-123",
                Instant.now(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    @Test
    @DisplayName("POST /api/v1/orders tạo đơn hàng thành công trả về 201 Created")
    void createOrder_success() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "123 Le Loi, Quan 1, TP.HCM",
                "Nguyen Van A",
                "0901234567",
                null,
                "VNPAY",
                OrderSourceType.REGULAR,
                null,
                null
        );

        when(orderService.createOrder(eq(userId), eq("idemp-key-123"), any(CreateOrderRequest.class)))
                .thenReturn(mockOrderResponse());

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .header("Idempotency-Key", "idemp-key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-20261023-ABCDEF"))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"));
    }

    @Test
    @DisplayName("POST /api/v1/orders thất bại khi shippingAddress bị trống trả về 400 Bad Request")
    void createOrder_validationError() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest(
                "",
                "Nguyen Van A",
                "0901234567",
                null,
                "VNPAY",
                OrderSourceType.REGULAR,
                null,
                null
        );

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/orders/{orderId} trả về 200 OK cùng chi tiết đơn hàng")
    void getOrderById_success() throws Exception {
        when(orderService.getOrderById(eq(orderId), eq(userId), anyBoolean()))
                .thenReturn(mockOrderResponse());

        mockMvc.perform(get("/api/v1/orders/" + orderId)
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-20261023-ABCDEF"));
    }

    @Test
    @DisplayName("GET /api/v1/orders trả về 200 OK cùng danh sách đơn hàng phân trang")
    void getMyOrders_success() throws Exception {
        PageResponse<OrderResponse> pageResponse = new PageResponse<>(
                List.of(mockOrderResponse()), 0, 10, 1L, 1, true, true
        );

        when(orderService.getMyOrders(eq(userId), any(), any(Pageable.class)))
                .thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("PATCH /api/v1/orders/{orderId}/status cập nhật trạng thái đơn hàng thành công")
    void changeOrderStatus_success() throws Exception {
        OrderStatusChangeRequest request = new OrderStatusChangeRequest(
                OrderStatus.PAID, "Thanh toan thanh cong"
        );

        OrderResponse paidResponse = new OrderResponse(
                orderId,
                "ORD-20261023-ABCDEF",
                userId,
                UUID.randomUUID(),
                OrderSourceType.REGULAR,
                null,
                null,
                OrderStatus.PAID,
                new BigDecimal("500000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("500000.00"),
                "123 Le Loi, Quan 1, TP.HCM",
                "Nguyen Van A",
                "0901234567",
                "idemp-key-123",
                Instant.now(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        when(orderService.changeOrderStatus(eq(orderId), eq(OrderStatus.PAID), eq(userId), eq("Thanh toan thanh cong")))
                .thenReturn(paidResponse);

        mockMvc.perform(patch("/api/v1/orders/" + orderId + "/status")
                        .with(jwt().jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }
}
