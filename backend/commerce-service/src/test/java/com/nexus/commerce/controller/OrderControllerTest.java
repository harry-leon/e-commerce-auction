package com.nexus.commerce.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.SecurityConfig;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.ResolveOrderDisputeRequest;
import com.nexus.commerce.dto.request.SubmitOrderDisputeRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderItemResponse;
import com.nexus.commerce.dto.response.OrderStatusHistoryResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.service.OrderService;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.common.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Kiểm thử lát cắt WebMvc cho OrderController (Issue HG-S1-4 / Milestone 3).
 *
 * <p>Chức năng:
 * Xác thực toàn bộ các endpoint RESTful quản lý đơn hàng:
 * <ul>
 *   <li>Lịch sử đơn hàng của Người mua (GET /api/v1/orders) kèm phân trang và bộ lọc.</li>
 *   <li>Chi tiết đơn hàng (GET /api/v1/orders/{orderId}) kèm snapshot mặt hàng và lịch sử.</li>
 *   <li>Danh sách đơn hàng của Người bán (GET /api/v1/orders/seller).</li>
 *   <li>Cập nhật trạng thái đơn hàng (PATCH /api/v1/orders/{orderId}/status).</li>
 *   <li>Hủy đơn hàng (POST /api/v1/orders/{orderId}/cancel).</li>
 * </ul>
 *
 * <p>Đầu vào và kết quả:
 * Giả lập các yêu cầu HTTP có/không có token Keycloak JWT, kiểm tra mã trạng thái HTTP
 * và cấu trúc phản hồi chuẩn Envelope ApiResponse.
 *
 * <p>Luồng xử lý:
 * MockMvc -> SecurityFilterChain (SecurityConfig) -> Method Security (@PreAuthorize) ->
 * OrderController -> MockitoBean OrderService -> GlobalExceptionHandler.
 *
 * <p>Quy tắc và lý do:
 * Tuân thủ bảo mật IAM (ROLE_BUYER, ROLE_SELLER, ROLE_ADMIN), kiểm soát validation dữ liệu,
 * và xử lý các tình huống biên (ngày tháng nghịch đảo, UUID dị tật, phân trang không hợp lệ).
 */
@WebMvcTest(OrderController.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
@ActiveProfiles("test")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private final UUID buyerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID sellerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID adminId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID orderId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    // =========================================================================
    // Helper Mock JWT & Data Builders
    // =========================================================================

    /**
     * Giả lập danh tính Người mua (ROLE_BUYER) trong Keycloak JWT.
     */
    private RequestPostProcessor buyerJwt(UUID userId) {
        return jwt()
                .jwt(j -> j.subject(userId.toString()).claim("preferred_username", "buyer_user"))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER"));
    }

    /**
     * Giả lập danh tính Người bán (ROLE_SELLER) trong Keycloak JWT.
     */
    private RequestPostProcessor sellerJwt(UUID userId) {
        return jwt()
                .jwt(j -> j.subject(userId.toString()).claim("preferred_username", "seller_user"))
                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"));
    }

    /**
     * Giả lập danh tính Quản trị viên (ROLE_ADMIN) trong Keycloak JWT.
     */
    private RequestPostProcessor adminJwt(UUID userId) {
        return jwt()
                .jwt(j -> j.subject(userId.toString()).claim("preferred_username", "admin_user"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /**
     * Tạo dữ liệu mẫu tóm tắt đơn hàng OrderSummaryResponse.
     */
    private OrderSummaryResponse mockOrderSummary(UUID id, OrderStatus status) {
        return OrderSummaryResponse.builder()
                .id(id)
                .orderNo("ORD-20261029-001")
                .buyerId(buyerId)
                .sellerId(sellerId)
                .sourceType(OrderSourceType.REGULAR)
                .status(status)
                .total(new BigDecimal("1500000.00"))
                .itemCount(1)
                .shippingAddress("123 Nguyen Hue, District 1, HCMC")
                .createdAt(Instant.parse("2026-10-29T00:00:00Z"))
                .build();
    }

    /**
     * Tạo dữ liệu mẫu chi tiết đơn hàng OrderDetailResponse hoàn chỉnh kèm snapshot mặt hàng và lịch sử.
     */
    private OrderDetailResponse mockOrderDetail(UUID id, OrderStatus status) {
        OrderItemResponse item = OrderItemResponse.builder()
                .id(UUID.randomUUID())
                .skuId(UUID.randomUUID())
                .productName("iPhone 15 Pro")
                .skuCode("IP15P-256-TI")
                .unitPrice(new BigDecimal("25000000.00"))
                .quantity(1)
                .subtotal(new BigDecimal("25000000.00"))
                .build();

        OrderStatusHistoryResponse history = OrderStatusHistoryResponse.builder()
                .id(UUID.randomUUID())
                .fromStatus(null)
                .toStatus(status)
                .reason("[SYSTEM] Tạo đơn hàng thành công")
                .changedAt(Instant.parse("2026-10-29T00:00:00Z"))
                .actor("SYSTEM")
                .build();

        return OrderDetailResponse.builder()
                .id(id)
                .orderNo("ORD-20261029-001")
                .buyerId(buyerId)
                .sellerId(sellerId)
                .sourceType(OrderSourceType.REGULAR)
                .auctionId(null)
                .voucherId(null)
                .status(status)
                .subtotal(new BigDecimal("25000000.00"))
                .shippingFee(new BigDecimal("30000.00"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("25030000.00"))
                .shippingAddress("123 Nguyen Hue, District 1, HCMC")
                .createdAt(Instant.parse("2026-10-29T00:00:00Z"))
                .items(List.of(item))
                .statusHistory(List.of(history))
                .build();
    }

    // =========================================================================
    // 1. Nhóm kiểm thử Lịch sử đơn hàng Người mua (GET /api/v1/orders)
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/orders - Lịch sử đơn hàng Người mua & Điều hướng")
    class BuyerOrderHistoryTests {

        /**
         * Kiểm tra từ chối truy cập 401 khi không truyền token xác thực.
         */
        @Test
        @DisplayName("Chưa đăng nhập -> Trả về 401 Unauthorized")
        void getBuyerOrders_unauthorized_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/orders"))
                    .andExpect(status().isUnauthorized());

            verify(orderService, never()).getBuyerOrders(any(), any(), any(LocalDateTime.class), any(LocalDateTime.class), any());
        }

        /**
         * Kiểm tra truy vấn danh sách đơn hàng mặc định thành công cho người mua hợp lệ.
         */
        @Test
        @DisplayName("Người mua đã đăng nhập -> Trả về 200 OK kèm danh sách phân trang chuẩn Envelope v2")
        void getBuyerOrders_authenticatedBuyer_returns200() throws Exception {
            Page<OrderSummaryResponse> page = new PageImpl<>(
                    List.of(mockOrderSummary(orderId, OrderStatus.PENDING_PAYMENT)),
                    PageRequest.of(0, 20),
                    1L
            );

            when(orderService.getBuyerOrders(
                    eq(buyerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].orderNo").value("ORD-20261029-001"))
                    .andExpect(jsonPath("$.data[0].status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.meta.pagination.page").value(0))
                    .andExpect(jsonPath("$.meta.pagination.totalElements").value(1));
        }

        /**
         * Kiểm tra chuyển tiếp đúng bộ lọc status và khoảng thời gian xuống tầng Service.
         */
        @Test
        @DisplayName("Truy vấn kèm bộ lọc status và ngày tháng -> Tham số được chuyển đúng xuống Service")
        void getBuyerOrders_withStatusAndDateFilters_parametersForwarded() throws Exception {
            Page<OrderSummaryResponse> page = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 10), 0L
            );

            when(orderService.getBuyerOrders(
                    eq(buyerId),
                    eq(OrderStatus.PAID),
                    any(LocalDateTime.class),
                    any(LocalDateTime.class),
                    any(Pageable.class)
            )).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("status", "PAID")
                            .param("startDate", "2026-10-01T00:00:00")
                            .param("endDate", "2026-10-29T23:59:59")
                            .param("page", "0")
                            .param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());

            verify(orderService).getBuyerOrders(
                    eq(buyerId),
                    eq(OrderStatus.PAID),
                    any(LocalDateTime.class),
                    any(LocalDateTime.class),
                    any(Pageable.class)
            );
        }

        /**
         * Kiểm tra xử lý trả về danh sách rỗng thành công khi không có đơn hàng nào.
         */
        @Test
        @DisplayName("Không có đơn hàng nào -> Trả về 200 OK với content rỗng")
        void getBuyerOrders_emptyResult_returns200WithEmptyContent() throws Exception {
            Page<OrderSummaryResponse> emptyPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0L
            );

            when(orderService.getBuyerOrders(
                    eq(buyerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(emptyPage);

            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty())
                    .andExpect(jsonPath("$.meta.pagination.totalElements").value(0));
        }

        /**
         * Kiểm tra tùy chỉnh phân trang và thứ tự sắp xếp thành công.
         */
        @Test
        @DisplayName("Tùy chỉnh phân trang sortBy và sortDirection -> Trả về 200 OK")
        void getBuyerOrders_customPaginationAndSorting_success() throws Exception {
            Page<OrderSummaryResponse> page = new PageImpl<>(
                    List.of(mockOrderSummary(orderId, OrderStatus.COMPLETED)),
                    PageRequest.of(1, 10),
                    15L
            );

            when(orderService.getBuyerOrders(
                    eq(buyerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("page", "1")
                            .param("size", "10")
                            .param("sortBy", "total")
                            .param("sortDirection", "ASC"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.meta.pagination.page").value(1))
                    .andExpect(jsonPath("$.meta.pagination.size").value(10));
        }

        /**
         * Kiểm tra lỗi validation khi truyền số trang âm.
         */
        @Test
        @DisplayName("Số trang âm (page = -1) -> Ràng buộc @Min báo lỗi 422 VALIDATION_ERROR")
        void getOrders_negativePage_returns422ValidationError() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("page", "-1"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Kiểm tra lỗi validation khi kích thước trang bằng 0.
         */
        @Test
        @DisplayName("Kích thước trang bằng 0 (size = 0) -> Ràng buộc @Min báo lỗi 422 VALIDATION_ERROR")
        void getOrders_zeroSize_returns422ValidationError() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("size", "0"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Kiểm tra lỗi validation khi kích thước trang vượt quá 100.
         */
        @Test
        @DisplayName("Kích thước trang vượt quá 100 (size = 101) -> Ràng buộc @Max báo lỗi 422 VALIDATION_ERROR")
        void getOrders_sizeExceeds100_returns422ValidationError() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("size", "101"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Kiểm tra lỗi khi ngày bắt đầu sau ngày kết thúc.
         */
        @Test
        @DisplayName("startDate sau endDate -> Ném INVALID_PARAM_VALUE trả về 400 Bad Request")
        void getOrders_startDateAfterEndDate_returns400BadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("startDate", "2026-10-29T10:00:00")
                            .param("endDate", "2026-10-01T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAM_VALUE"));
        }

        /**
         * Kiểm tra lỗi khi truyền sai giá trị enum trạng thái đơn hàng.
         */
        @Test
        @DisplayName("Truyền status không tồn tại trong enum -> Báo lỗi 400 INVALID_PARAM_TYPE")
        void getOrders_invalidStatusEnum_returns400BadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("status", "INVALID_STATUS_VALUE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAM_TYPE"));
        }

        /**
         * Kiểm tra Người bán truyền role=seller điều hướng gọi phương thức getSellerOrders.
         */
        @Test
        @DisplayName("Người bán gọi /orders?role=seller -> Điều hướng gọi getSellerOrders")
        void getOrders_withRoleSellerParam_asSeller_delegatesToSellerOrders() throws Exception {
            Page<OrderSummaryResponse> sellerPage = new PageImpl<>(
                    List.of(mockOrderSummary(orderId, OrderStatus.PROCESSING)),
                    PageRequest.of(0, 20),
                    1L
            );

            when(orderService.getSellerOrders(
                    eq(sellerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(sellerPage);

            mockMvc.perform(get("/api/v1/orders")
                            .with(sellerJwt(sellerId))
                            .param("role", "seller"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].status").value("PROCESSING"));

            verify(orderService).getSellerOrders(
                    eq(sellerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            );
        }

        /**
         * Kiểm tra Quản trị viên truyền role=seller được phép điều hướng sang getSellerOrders.
         */
        @Test
        @DisplayName("Quản trị viên gọi /orders?role=seller -> Được phép điều hướng sang getSellerOrders")
        void getOrders_withRoleSellerParam_asAdmin_delegatesToSellerOrders() throws Exception {
            Page<OrderSummaryResponse> adminPage = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0L
            );

            when(orderService.getSellerOrders(
                    eq(adminId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(adminPage);

            mockMvc.perform(get("/api/v1/orders")
                            .with(adminJwt(adminId))
                            .param("role", "seller"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(orderService).getSellerOrders(
                    eq(adminId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            );
        }

        /**
         * Kiểm tra Quản trị viên gọi /orders?role=admin được điều hướng sang getAdminOrders.
         */
        @Test
        @DisplayName("Quản trị viên gọi /orders?role=admin -> Điều hướng gọi getAdminOrders")
        void getOrders_withRoleAdminParam_asAdmin_delegatesToAdminOrders() throws Exception {
            Page<OrderSummaryResponse> adminOrders = new PageImpl<>(
                    List.of(mockOrderSummary(orderId, OrderStatus.PAID)),
                    PageRequest.of(0, 20),
                    1L
            );

            when(orderService.getAdminOrders(
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(adminOrders);

            mockMvc.perform(get("/api/v1/orders")
                            .with(adminJwt(adminId))
                            .param("role", "admin"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].status").value("PAID"));

            verify(orderService).getAdminOrders(
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            );
        }

        /**
         * Kiểm tra Người mua gọi /orders?role=admin bị từ chối 403 Forbidden.
         */
        @Test
        @DisplayName("Người mua gọi /orders?role=admin -> Bị từ chối 403 AUTH_FORBIDDEN")
        void getOrders_withRoleAdminParam_asBuyer_returns403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("role", "admin"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

            verify(orderService, never()).getAdminOrders(any(), any(LocalDateTime.class), any(LocalDateTime.class), any());
        }

        /**
         * Kiểm tra Người mua thuần túy gọi /orders?role=seller bị từ chối 403 Forbidden.
         */
        @Test
        @DisplayName("Người mua thuần túy gọi /orders?role=seller -> Bị từ chối 403 AUTH_FORBIDDEN")
        void getOrders_withRoleSellerParam_asBuyer_returns403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("role", "seller"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

            verify(orderService, never()).getSellerOrders(any(), any(), any(LocalDateTime.class), any(LocalDateTime.class), any());
        }

        /**
         * Kiểm tra từ chối tham số role không hợp lệ ngoài buyer, seller và admin.
         */
        @Test
        @DisplayName("Tham số role không hợp lệ (role = 'unknown') -> Báo lỗi 400 INVALID_PARAM_VALUE")
        void getOrders_invalidRoleParam_returns400BadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/orders")
                            .with(buyerJwt(buyerId))
                            .param("role", "unknown"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAM_VALUE"));
        }
    }

    // =========================================================================
    // 2. Nhóm kiểm thử Chi tiết đơn hàng (GET /api/v1/orders/{orderId})
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/orders/{orderId} - Xem chi tiết đơn hàng")
    class OrderDetailTests {

        /**
         * Kiểm tra từ chối 401 khi xem chi tiết mà chưa xác thực.
         */
        @Test
        @DisplayName("Chưa đăng nhập -> Trả về 401 Unauthorized")
        void getOrderDetail_unauthorized_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Kiểm tra lấy thông tin chi tiết đơn hàng thành công kèm snapshot và lịch sử.
         */
        @Test
        @DisplayName("Chủ sở hữu hợp lệ -> Trả về 200 OK kèm snapshot items và timeline history")
        void getOrderDetail_validOwner_returns200WithSnapshotAndHistory() throws Exception {
            OrderDetailResponse detail = mockOrderDetail(orderId, OrderStatus.PENDING_PAYMENT);

            when(orderService.getOrderDetail(eq(orderId), eq(buyerId), any()))
                    .thenReturn(detail);

            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(orderId.toString()))
                    .andExpect(jsonPath("$.data.orderNo").value("ORD-20261029-001"))
                    .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.data.items[0].productName").value("iPhone 15 Pro"))
                    .andExpect(jsonPath("$.data.items[0].skuCode").value("IP15P-256-TI"))
                    .andExpect(jsonPath("$.data.statusHistory[0].toStatus").value("PENDING_PAYMENT"))
                    .andExpect(jsonPath("$.data.statusHistory[0].actor").value("SYSTEM"));
        }

        /**
         * Kiểm tra chặn truy cập chéo đơn hàng của người khác (403 Forbidden).
         */
        @Test
        @DisplayName("Không phải chủ sở hữu -> Service ném AUTH_FORBIDDEN -> Trả về 403 Forbidden")
        void getOrderDetail_nonOwnerAccess_returns403Forbidden() throws Exception {
            when(orderService.getOrderDetail(eq(orderId), eq(buyerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.AUTH_FORBIDDEN,
                            "Bạn không có quyền truy cập vào thông tin chi tiết đơn hàng này"
                    ));

            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));
        }

        /**
         * Kiểm tra thông báo lỗi 404 khi không tìm thấy đơn hàng.
         */
        @Test
        @DisplayName("Đơn hàng không tồn tại -> Service ném ORDER_NOT_FOUND -> Trả về 404 Not Found")
        void getOrderDetail_orderNotFound_returns404NotFound() throws Exception {
            when(orderService.getOrderDetail(eq(orderId), eq(buyerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.ORDER_NOT_FOUND,
                            "Không tìm thấy đơn hàng với mã định danh: " + orderId
                    ));

            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }

        /**
         * Kiểm tra báo lỗi 400 Bad Request khi UUID trên path sai định dạng.
         */
        @Test
        @DisplayName("Mã orderId sai định dạng UUID -> Trả về 400 Bad Request kèm mã INVALID_PARAM_TYPE")
        void getOrderDetail_invalidUuid_returns400BadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/orders/{orderId}", "invalid-uuid-format")
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAM_TYPE"));
        }
    }

    // =========================================================================
    // 3. Nhóm kiểm thử Danh sách đơn hàng Người bán (GET /api/v1/orders/seller)
    // =========================================================================

    @Nested
    @DisplayName("GET /api/v1/orders/seller - Danh sách đơn hàng Người bán")
    class SellerOrderListTests {

        /**
         * Kiểm tra từ chối 401 khi chưa xác thực.
         */
        @Test
        @DisplayName("Chưa đăng nhập -> Trả về 401 Unauthorized")
        void getSellerOrders_unauthorized_returns401() throws Exception {
            mockMvc.perform(get("/api/v1/orders/seller"))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Kiểm tra Người bán (ROLE_SELLER) truy vấn danh sách đơn hàng thành công.
         */
        @Test
        @DisplayName("Người bán đã đăng nhập (ROLE_SELLER) -> Trả về 200 OK kèm danh sách đơn")
        void getSellerOrders_authenticatedSeller_returns200() throws Exception {
            Page<OrderSummaryResponse> page = new PageImpl<>(
                    List.of(mockOrderSummary(orderId, OrderStatus.PROCESSING)),
                    PageRequest.of(0, 20),
                    1L
            );

            when(orderService.getSellerOrders(
                    eq(sellerId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders/seller")
                            .with(sellerJwt(sellerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data[0].status").value("PROCESSING"));
        }

        /**
         * Kiểm tra Quản trị viên (ROLE_ADMIN) truy vấn danh sách đơn bán thành công.
         */
        @Test
        @DisplayName("Quản trị viên (ROLE_ADMIN) -> Trả về 200 OK")
        void getSellerOrders_adminRole_returns200() throws Exception {
            Page<OrderSummaryResponse> page = new PageImpl<>(
                    Collections.emptyList(), PageRequest.of(0, 20), 0L
            );

            when(orderService.getSellerOrders(
                    eq(adminId),
                    isNull(),
                    (LocalDateTime) isNull(),
                    (LocalDateTime) isNull(),
                    any(Pageable.class)
            )).thenReturn(page);

            mockMvc.perform(get("/api/v1/orders/seller")
                            .with(adminJwt(adminId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        /**
         * Kiểm tra Người mua (ROLE_BUYER) truy cập route Người bán bị chặn 403 Forbidden.
         */
        @Test
        @DisplayName("Người mua (chỉ có ROLE_BUYER) truy cập -> Method Security chặn -> Trả về 403 Forbidden")
        void getSellerOrders_buyerRole_returns403Forbidden() throws Exception {
            mockMvc.perform(get("/api/v1/orders/seller")
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

            verify(orderService, never()).getSellerOrders(any(), any(), any(LocalDateTime.class), any(LocalDateTime.class), any());
        }

        /**
         * Kiểm tra lỗi khi ngày bắt đầu sau ngày kết thúc trên route Người bán.
         */
        @Test
        @DisplayName("Người bán truyền startDate sau endDate -> Trả về 400 INVALID_PARAM_VALUE")
        void getSellerOrders_startDateAfterEndDate_returns400BadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/orders/seller")
                            .with(sellerJwt(sellerId))
                            .param("startDate", "2026-10-29T10:00:00")
                            .param("endDate", "2026-10-01T00:00:00"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("INVALID_PARAM_VALUE"));
        }
    }

    // =========================================================================
    // 4. Nhóm kiểm thử Cập nhật trạng thái (PATCH /api/v1/orders/{orderId}/status)
    // =========================================================================

    @Nested
    @DisplayName("PATCH /api/v1/orders/{orderId}/status - Cập nhật trạng thái đơn hàng")
    class UpdateOrderStatusTests {

        /**
         * Kiểm tra từ chối 401 khi cập nhật trạng thái mà chưa xác thực.
         */
        @Test
        @DisplayName("Chưa đăng nhập -> Trả về 401 Unauthorized")
        void updateOrderStatus_unauthorized_returns401() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, "Đang xử lý");

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Kiểm tra Người bán cập nhật trạng thái đơn hàng hợp lệ thành công.
         */
        @Test
        @DisplayName("Người bán (ROLE_SELLER) cập nhật hợp lệ -> Trả về 200 OK")
        void updateOrderStatus_sellerRole_returns200() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, "Bắt đầu đóng gói");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.PROCESSING);

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenReturn(response);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        }

        /**
         * Kiểm tra Quản trị viên cập nhật trạng thái đơn hàng thành công.
         */
        @Test
        @DisplayName("Quản trị viên (ROLE_ADMIN) cập nhật hợp lệ -> Trả về 200 OK")
        void updateOrderStatus_adminRole_returns200() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.REFUNDED, "Hoàn tiền");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.REFUNDED);

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(adminId), any()))
                    .thenReturn(response);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED"));
        }

        /**
         * Kiểm tra Người mua không được phép gọi API cập nhật trạng thái (403 Forbidden).
         */
        @Test
        @DisplayName("Người mua (ROLE_BUYER) cố gắng cập nhật trạng thái -> Bị chặn 403 Forbidden")
        void updateOrderStatus_buyerRole_returns403Forbidden() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.COMPLETED, "Hoàn tất");

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

            verify(orderService, never()).updateOrderStatus(any(), any(), any(), any());
        }

        /**
         * Kiểm tra lỗi validation khi trường status bị null trong request body.
         */
        @Test
        @DisplayName("Trường status bị null -> Validation chặn trả về 422 VALIDATION_ERROR")
        void updateOrderStatus_nullStatus_returns422ValidationError() throws Exception {
            String invalidJson = "{\"status\": null, \"reason\": \"Thiếu status\"}";

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Kiểm tra lỗi định dạng JSON khi chuỗi status không khớp enum OrderStatus.
         */
        @Test
        @DisplayName("Chuỗi status không khớp enum OrderStatus -> Lỗi cú pháp JSON trả về 400 BAD_REQUEST")
        void updateOrderStatus_invalidStatusEnumString_returns400BadRequest() throws Exception {
            String invalidJson = "{\"status\": \"INVALID_STATUS_VALUE\", \"reason\": \"Sai enum\"}";

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invalidJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
        }

        /**
         * Kiểm tra lỗi xung đột trạng thái khi chuyển trạng thái sai ma trận quy định (409 Conflict).
         */
        @Test
        @DisplayName("Bước nhảy trạng thái vi phạm ma trận -> Service ném ORDER_STATUS_INVALID -> Trả về 409 Conflict")
        void updateOrderStatus_invalidStateTransition_returns409Conflict() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.DELIVERED, "Nhảy cóc");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.ORDER_STATUS_INVALID,
                            "Không thể chuyển trạng thái đơn hàng từ PENDING_PAYMENT sang DELIVERED"
                    ));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_STATUS_INVALID"));
        }

        /**
         * Kiểm tra báo lỗi 404 khi cập nhật đơn hàng không tồn tại.
         */
        @Test
        @DisplayName("Cập nhật đơn hàng không tồn tại -> Service ném ORDER_NOT_FOUND -> Trả về 404 Not Found")
        void updateOrderStatus_orderNotFound_returns404NotFound() throws Exception {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, "Xử lý");

            when(orderService.updateOrderStatus(eq(orderId), any(UpdateOrderStatusRequest.class), eq(sellerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.ORDER_NOT_FOUND,
                            "Không tìm thấy đơn hàng"
                    ));

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }

        /**
         * Kiểm tra lỗi validation khi lý do cập nhật trạng thái dài hơn 500 ký tự.
         */
        @Test
        @DisplayName("Lý do cập nhật vượt quá 500 ký tự -> Trả về 422 VALIDATION_ERROR")
        void updateOrderStatus_reasonTooLong_returns422ValidationError() throws Exception {
            String longReason = "A".repeat(501);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, longReason);

            mockMvc.perform(patch("/api/v1/orders/{orderId}/status", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    // =========================================================================
    // 5. Nhóm kiểm thử Hủy đơn hàng (POST /api/v1/orders/{orderId}/cancel)
    // =========================================================================

    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/cancel - Hủy đơn hàng")
    class CancelOrderTests {

        /**
         * Kiểm tra từ chối 401 khi hủy đơn mà chưa đăng nhập.
         */
        @Test
        @DisplayName("Chưa đăng nhập -> Trả về 401 Unauthorized")
        void cancelOrder_unauthorized_returns401() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Thay đổi nhu cầu");

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * Kiểm tra Người mua là chủ đơn hủy đơn thành công (200 OK).
         */
        @Test
        @DisplayName("Người mua (ROLE_BUYER) hủy đơn thành công -> Trả về 200 OK với status CANCELLED")
        void cancelOrder_buyerOwner_returns200() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Khách hàng đổi ý");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.CANCELLED);

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        /**
         * Kiểm tra Quản trị viên hủy đơn hàng thành công (200 OK).
         */
        @Test
        @DisplayName("Quản trị viên (ROLE_ADMIN) hủy đơn thành công -> Trả về 200 OK")
        void cancelOrder_adminRole_returns200() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Admin hủy can thiệp");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.CANCELLED);

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(adminId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        }

        /**
         * Kiểm tra Người bán (ROLE_SELLER) cố gọi API hủy đơn của Buyer bị chặn 403 Forbidden.
         */
        @Test
        @DisplayName("Người bán (chỉ có ROLE_SELLER) cố gọi API hủy của Buyer -> Bị chặn 403 Forbidden")
        void cancelOrder_sellerRole_returns403Forbidden() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Người bán hủy");

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(sellerJwt(sellerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("AUTH_FORBIDDEN"));

            verify(orderService, never()).cancelOrder(any(), any(), any(), any());
        }

        /**
         * Kiểm tra chặn hủy đơn khi đơn đã xuất kho hoặc đang vận chuyển (409 Conflict).
         */
        @Test
        @DisplayName("Đơn hàng đã xuất kho hoặc vận chuyển -> Service ném ORDER_CANNOT_CANCEL -> Trả về 409 Conflict")
        void cancelOrder_shippedOrProcessing_returns409Conflict() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Hủy đơn sau xuất kho");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.ORDER_CANNOT_CANCEL,
                            "Đơn hàng đang giao hoặc đã hoàn tất, không thể hủy"
                    ));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_CANNOT_CANCEL"));
        }

        /**
         * Kiểm tra báo lỗi 404 khi hủy đơn hàng không tồn tại.
         */
        @Test
        @DisplayName("Hủy đơn hàng không tồn tại -> Service ném ORDER_NOT_FOUND -> Trả về 404 Not Found")
        void cancelOrder_orderNotFound_returns404NotFound() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("Hủy đơn");

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenThrow(new BusinessException(
                            ErrorCode.ORDER_NOT_FOUND,
                            "Không tìm thấy đơn hàng"
                    ));

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("ORDER_NOT_FOUND"));
        }

        /**
         * Kiểm tra client gửi yêu cầu hủy đơn không có request body -> Tự động dùng lý do mặc định và trả về 200 OK.
         */
        @Test
        @DisplayName("Client gửi yêu cầu hủy không có body -> Tự động dùng lý do mặc định và trả về 200 OK")
        void cancelOrder_withoutBody_defaultsReasonAndReturns200() throws Exception {
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.CANCELLED);

            when(orderService.cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("CANCELLED"));

            verify(orderService).cancelOrder(eq(orderId), any(CancelOrderRequest.class), eq(buyerId), any());
        }

        /**
         * Kiểm tra lỗi validation khi lý do hủy đơn hàng bị để trống hoặc toàn khoảng trắng.
         */
        @Test
        @DisplayName("Lý do hủy để trống -> Ràng buộc @NotBlank báo lỗi 422 VALIDATION_ERROR")
        void cancelOrder_blankReason_returns422ValidationError() throws Exception {
            CancelOrderRequest request = new CancelOrderRequest("   ");

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Kiểm tra lỗi validation khi lý do hủy đơn hàng dài hơn 500 ký tự.
         */
        @Test
        @DisplayName("Lý do hủy vượt quá 500 ký tự -> Ràng buộc @Size báo lỗi 422 VALIDATION_ERROR")
        void cancelOrder_reasonTooLong_returns422ValidationError() throws Exception {
            String longReason = "X".repeat(501);
            CancelOrderRequest request = new CancelOrderRequest(longReason);

            mockMvc.perform(post("/api/v1/orders/{orderId}/cancel", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }

    /**
     * Nhóm kiểm thử giải quyết tranh chấp ký quỹ đơn hàng (POST /api/v1/orders/{orderId}/resolve-dispute và /dispute).
     *
     * <p>Mục đích: Bảo vệ quy tắc bảo mật chỉ Admin được phán quyết tranh chấp và kiểm soát luồng khiếu nại của Buyer.
     */
    @Nested
    @DisplayName("POST /api/v1/orders/{orderId}/resolve-dispute & /dispute - Xử lý tranh chấp ký quỹ")
    class DisputeResolutionTests {

        /**
         * Mục đích: Admin phán quyết hoàn tiền (refunded) thành công, cập nhật trạng thái REFUNDED.
         * Đầu vào: orderId, resolution = 'refunded', note giải trình.
         * Kết quả: Trả về 200 OK với thông tin đơn hàng đã hoàn tiền.
         */
        @Test
        @DisplayName("Admin phán quyết hoàn tiền cho người mua (refunded) -> Trả về 200 OK")
        void resolveDispute_adminRefund_returns200Ok() throws Exception {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("refunded", "Hàng bể vỡ khi vận chuyển");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.REFUNDED);

            when(orderService.resolveDispute(eq(orderId), any(ResolveOrderDisputeRequest.class), eq(adminId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/resolve-dispute", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("REFUNDED"));

            verify(orderService).resolveDispute(eq(orderId), any(ResolveOrderDisputeRequest.class), eq(adminId), any());
        }

        /**
         * Mục đích: Admin phán quyết giải ngân cho người bán (released) thành công.
         * Đầu vào: orderId, resolution = 'released'.
         * Kết quả: Trả về 200 OK.
         */
        @Test
        @DisplayName("Admin phán quyết giải ngân cho người bán (released) -> Trả về 200 OK")
        void resolveDispute_adminRelease_returns200Ok() throws Exception {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("released", "Bằng chứng người bán hợp lệ");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.COMPLETED);

            when(orderService.resolveDispute(eq(orderId), any(ResolveOrderDisputeRequest.class), eq(adminId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/resolve-dispute", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        }

        /**
         * Mục đích: Admin phán quyết hoàn tiền một phần (partial_refund) thành công.
         * Đầu vào: orderId, resolution = 'partial_refund'.
         * Kết quả: Trả về 200 OK.
         */
        @Test
        @DisplayName("Admin phán quyết hoàn tiền một phần (partial_refund) -> Trả về 200 OK")
        void resolveDispute_adminPartialRefund_returns200Ok() throws Exception {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("partial_refund", "Thỏa thuận bồi thường một phần");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.COMPLETED);

            when(orderService.resolveDispute(eq(orderId), any(ResolveOrderDisputeRequest.class), eq(adminId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/resolve-dispute", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));
        }

        /**
         * Mục đích: Chặn người dùng không có quyền ADMIN gọi API giải quyết tranh chấp.
         * Đầu vào: Token BUYER cố gắng gọi resolve-dispute.
         * Kết quả: Trả về 403 Forbidden.
         */
        @Test
        @DisplayName("Người mua gọi API giải quyết tranh chấp -> Bị chặn 403 Forbidden")
        void resolveDispute_buyerCalling_returns403Forbidden() throws Exception {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("refunded", "Buyer tự duyệt");

            mockMvc.perform(post("/api/v1/orders/{orderId}/resolve-dispute", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());

            verify(orderService, never()).resolveDispute(any(), any(), any(), any());
        }

        /**
         * Mục đích: Kiểm tra validation khi resolution không đúng các giá trị cho phép.
         * Đầu vào: resolution = 'invalid_action'.
         * Kết quả: Trả về 422 VALIDATION_ERROR.
         */
        @Test
        @DisplayName("Resolution sai định dạng -> Ràng buộc Pattern ném 422 VALIDATION_ERROR")
        void resolveDispute_invalidResolution_returns422ValidationError() throws Exception {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("invalid_action", "Sai");

            mockMvc.perform(post("/api/v1/orders/{orderId}/resolve-dispute", orderId)
                            .with(adminJwt(adminId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        /**
         * Mục đích: Người mua gửi khiếu nại đơn hàng thành công.
         * Đầu vào: orderId, reason khiếu nại.
         * Kết quả: Trả về 200 OK.
         */
        @Test
        @DisplayName("Người mua gửi khiếu nại đơn hàng -> Trả về 200 OK")
        void submitDispute_buyer_returns200Ok() throws Exception {
            SubmitOrderDisputeRequest request = new SubmitOrderDisputeRequest("Sản phẩm không đúng mô tả");
            OrderDetailResponse response = mockOrderDetail(orderId, OrderStatus.DELIVERED);

            when(orderService.submitDispute(eq(orderId), any(SubmitOrderDisputeRequest.class), eq(buyerId), any()))
                    .thenReturn(response);

            mockMvc.perform(post("/api/v1/orders/{orderId}/dispute", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true));

            verify(orderService).submitDispute(eq(orderId), any(SubmitOrderDisputeRequest.class), eq(buyerId), any());
        }

        /**
         * Mục đích: Lý do khiếu nại bị để trống -> Báo lỗi 422.
         */
        @Test
        @DisplayName("Lý do khiếu nại để trống -> Báo lỗi 422 VALIDATION_ERROR")
        void submitDispute_blankReason_returns422() throws Exception {
            SubmitOrderDisputeRequest request = new SubmitOrderDisputeRequest("   ");

            mockMvc.perform(post("/api/v1/orders/{orderId}/dispute", orderId)
                            .with(buyerJwt(buyerId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }
    }
}
