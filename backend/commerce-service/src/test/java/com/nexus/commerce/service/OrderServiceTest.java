package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.CreateAuctionOrderRequest;
import com.nexus.commerce.dto.request.ResolveOrderDisputeRequest;
import com.nexus.commerce.dto.request.SubmitOrderDisputeRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
import com.nexus.commerce.dto.response.AuctionOrderResponse;
import com.nexus.commerce.dto.response.OrderDetailResponse;
import com.nexus.commerce.dto.response.OrderItemResponse;
import com.nexus.commerce.dto.response.OrderStatusHistoryResponse;
import com.nexus.commerce.dto.response.OrderSummaryResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.entity.OrderStatusHistory;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.impl.OrderServiceImpl;
import com.nexus.commerce.statemachine.OrderStatusStateMachine;
import com.nexus.common.exception.BusinessException;
import com.nexus.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Bộ kiểm thử đơn vị toàn diện cho OrderService (OrderServiceImpl).
 *
 * <p>Chức năng:
 * Kiểm thử cách ly tầng nghiệp vụ quản lý đơn hàng theo kiến trúc Clean Architecture:
 * <ul>
 *   <li>Truy vấn lịch sử đơn hàng của Người mua (getBuyerOrders) với các bộ lọc phân trang, trạng thái, thời gian.</li>
 *   <li>Xem chi tiết đơn hàng (getOrderDetail) kèm kiểm tra phân quyền sở hữu nghiêm ngặt (Buyer, Seller, Admin).</li>
 *   <li>Truy vấn đơn hàng của Người bán (getSellerOrders) giới hạn tuyệt đối theo sellerId.</li>
 *   <li>Cập nhật trạng thái đơn hàng (updateOrderStatus) với xác thực qua OrderStatusStateMachine, ghi log kiểm toán và idempotency.</li>
 *   <li>Hủy đơn hàng (cancelOrder) đối với Người mua và Admin, chặn hủy khi đã đóng gói/vận chuyển.</li>
 * </ul>
 *
 * <p>Quy chuẩn chất lượng:
 * Tuân thủ Checkstyle (0 violations, no star imports), JUnit 5, Mockito 5 và hướng dẫn comment tiếng Việt theo AGENTS.md.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService Unit Test Suite - Kiểm thử tầng nghiệp vụ đơn hàng")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OrderStatusStateMachine orderStatusStateMachine;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private CatalogClient catalogClient;

    @Captor
    private ArgumentCaptor<OrderStatusHistory> historyCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private OrderServiceImpl orderService;

    private final UUID buyerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID strangerId = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
                orderRepository,
                orderStatusHistoryRepository,
                orderStatusStateMachine,
                orderMapper,
                org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                catalogClient
        );
    }

    /**
     * Phương thức tiện ích tạo thực thể Order mẫu phục vụ kiểm thử.
     */
    private Order buildMockOrder(UUID id, UUID bId, UUID sId, OrderStatus status) {
        Order order = Order.builder()
                .id(id)
                .orderNo("ORD-20261029-001")
                .buyerId(bId)
                .sellerId(sId)
                .sourceType(OrderSourceType.REGULAR)
                .status(status)
                .subtotal(new BigDecimal("200000.00"))
                .shippingFee(new BigDecimal("30000.00"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("230000.00"))
                .shippingAddress("123 Nguyen Hue, Quan 1, TP Ho Chi Minh")
                .createdAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .items(new ArrayList<>())
                .statusHistory(new ArrayList<>())
                .build();

        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID())
                .order(order)
                .skuId(UUID.randomUUID())
                .productName("Ao thun Nexus")
                .skuCode("NEX-TEE-01")
                .unitPrice(new BigDecimal("100000.00"))
                .quantity(2)
                .build();
        order.addItem(item);

        OrderStatusHistory history = OrderStatusHistory.builder()
                .id(UUID.randomUUID())
                .order(order)
                .fromStatus(null)
                .toStatus(OrderStatus.PENDING_PAYMENT)
                .reason("Khoi tao don hang")
                .changedAt(Instant.now().minus(1, ChronoUnit.HOURS))
                .build();
        order.addStatusHistory(history);

        return order;
    }

    private OrderSummaryResponse buildMockSummaryResponse(Order order) {
        return new OrderSummaryResponse(
                order.getId(),
                order.getOrderNo(),
                order.getBuyerId(),
                order.getSellerId(),
                order.getSourceType(),
                order.getStatus(),
                order.getTotal(),
                order.getItems() != null ? order.getItems().size() : 0,
                order.getShippingAddress(),
                order.getCreatedAt(), order.getEstimatedTotal(), order.isPriceFinalized(), order.getFulfillmentStatus()
        );
    }

    private OrderDetailResponse buildMockDetailResponse(Order order) {
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem it : order.getItems()) {
                itemResponses.add(new OrderItemResponse(
                        it.getId(),
                        it.getSkuId(),
                        it.getProductName(),
                        it.getSkuCode(),
                        it.getUnitPrice(),
                        it.getQuantity(),
                        it.getSubtotal(), it.getEstimatedUnitPrice(), null
                ));
            }
        }
        List<OrderStatusHistoryResponse> historyResponses = new ArrayList<>();
        if (order.getStatusHistory() != null) {
            for (OrderStatusHistory h : order.getStatusHistory()) {
                historyResponses.add(new OrderStatusHistoryResponse(
                        h.getId(),
                        h.getFromStatus(),
                        h.getToStatus(),
                        h.getReason(),
                        h.getChangedAt(),
                        "SYSTEM"
                ));
            }
        }
        return new OrderDetailResponse(
                order.getId(),
                order.getOrderNo(),
                order.getBuyerId(),
                order.getSellerId(),
                order.getSourceType(),
                order.getAuctionId(),
                order.getVoucherId(),
                order.getStatus(),
                order.getSubtotal(),
                order.getShippingFee(),
                order.getDiscount(),
                order.getTotal(),
                order.getShippingAddress(),
                order.getCreatedAt(),
                itemResponses,
                historyResponses, order.getEstimatedSubtotal(), order.getEstimatedShippingFee(),
                order.getEstimatedDiscount(), order.getEstimatedTotal(), order.isPriceFinalized(), order.getFulfillmentStatus()
        );
    }

    // =========================================================================
    // 1. NHÓM GET BUYER ORDERS
    // =========================================================================
    @Nested
    @DisplayName("getBuyerOrders() - Lấy danh sách lịch sử đơn hàng của người mua")
    class GetBuyerOrdersTests {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("Lấy danh sách đơn mua mặc định không có bộ lọc trạng thái và ngày")
        void test_getBuyerOrders_defaultFilter_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);
            OrderSummaryResponse summaryResponse = buildMockSummaryResponse(order);

            when(orderRepository.findByBuyerId(buyerId, pageable)).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(summaryResponse);

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            assertEquals(orderId, response.getContent().get(0).id());
            assertEquals(1, response.getTotalElements());
            verify(orderRepository, times(1)).findByBuyerId(buyerId, pageable);
        }

        @Test
        @DisplayName("Lấy danh sách đơn mua có lọc theo trạng thái PAID")
        void test_getBuyerOrders_withStatusFilter_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);
            OrderSummaryResponse summaryResponse = buildMockSummaryResponse(order);

            when(orderRepository.findByBuyerIdAndStatus(buyerId, OrderStatus.PAID, pageable)).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(summaryResponse);

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, OrderStatus.PAID, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            assertEquals(OrderStatus.PAID, response.getContent().get(0).status());
            verify(orderRepository, times(1)).findByBuyerIdAndStatus(buyerId, OrderStatus.PAID, pageable);
        }

        @Test
        @DisplayName("Lấy danh sách đơn mua có lọc theo khoảng ngày tạo đơn")
        void test_getBuyerOrders_withDateRangeFilter_success() {
            Instant start = Instant.now().minus(7, ChronoUnit.DAYS);
            Instant end = Instant.now();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findByBuyerIdAndCreatedAtBetween(buyerId, start, end, pageable))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findByBuyerIdAndCreatedAtBetween(buyerId, start, end, pageable);
        }

        @Test
        @DisplayName("Lấy danh sách đơn mua kết hợp đồng thời cả trạng thái và khoảng ngày")
        void test_getBuyerOrders_withCombinedFilter_success() {
            Instant start = Instant.now().minus(3, ChronoUnit.DAYS);
            Instant end = Instant.now();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PROCESSING);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findByBuyerIdAndStatusAndCreatedAtBetween(
                    buyerId, OrderStatus.PROCESSING, start, end, pageable
            )).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, OrderStatus.PROCESSING, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findByBuyerIdAndStatusAndCreatedAtBetween(
                    buyerId, OrderStatus.PROCESSING, start, end, pageable
            );
        }

        @Test
        @DisplayName("Xử lý an toàn khi người mua chưa có đơn hàng nào (kết quả rỗng)")
        void test_getBuyerOrders_emptyResult_returnsEmptyPageResponse() {
            when(orderRepository.findByBuyerId(buyerId, pageable)).thenReturn(Page.empty(pageable));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertTrue(response.getContent().isEmpty());
            assertEquals(0, response.getTotalElements());
            assertEquals(0, response.getTotalPages());
        }

        @Test
        @DisplayName("Ánh xạ chính xác các trường metadata phân trang (page, size, total, isFirst, isLast)")
        void test_getBuyerOrders_paginationMetadata_mappedCorrectly() {
            Pageable pageRequest = PageRequest.of(1, 5);
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageRequest, 12);

            when(orderRepository.findByBuyerId(buyerId, pageRequest)).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, (Instant) null, null, pageRequest
            );

            assertNotNull(response);
            assertEquals(1, response.getNumber());
            assertEquals(5, response.getSize());
            assertEquals(12, response.getTotalElements());
            assertEquals(3, response.getTotalPages());
            assertFalse(response.isFirst());
            assertFalse(response.isLast());
        }

        @Test
        @DisplayName("Khoảng thời gian không hợp lệ (startDate > endDate) ném ngoại lệ INVALID_PARAM_VALUE")
        void test_getBuyerOrders_invalidDateRange_throwsException() {
            Instant start = Instant.now();
            Instant end = start.minus(1, ChronoUnit.DAYS);

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getBuyerOrders(buyerId, null, start, end, pageable)
            );

            assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), ex.getCode());
        }

        @Test
        @DisplayName("Gọi getBuyerOrders thông qua overload LocalDateTime chuyển đổi mốc thời gian chính xác")
        void test_getBuyerOrders_localDateTimeOverload_success() {
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
            LocalDateTime end = LocalDateTime.of(2026, 10, 29, 23, 59);
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findByBuyerIdAndCreatedAtBetween(any(), any(), any(), any()))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
        }
    }

    // =========================================================================
    // 2. NHÓM GET ORDER DETAIL
    // =========================================================================
    @Nested
    @DisplayName("getOrderDetail() - Xem chi tiết đơn hàng và kiểm tra quyền sở hữu")
    class GetOrderDetailTests {

        @Test
        @DisplayName("Người mua sở hữu đơn hàng (Owner Buyer) xem chi tiết thành công")
        void test_getOrderDetail_buyerOwner_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(
                    orderId, buyerId, List.of("ROLE_BUYER")
            );

            assertNotNull(result);
            assertEquals(orderId, result.id());
            assertEquals(buyerId, result.buyerId());
            verify(orderRepository, times(1)).findWithItemsById(orderId);
            verify(orderMapper, times(1)).toDetailResponse(order);
        }

        @Test
        @DisplayName("Người bán của đơn hàng (Owner Seller) xem chi tiết thành công")
        void test_getOrderDetail_sellerOwner_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(
                    orderId, sellerId, List.of("ROLE_SELLER")
            );

            assertNotNull(result);
            assertEquals(sellerId, result.sellerId());
        }

        @Test
        @DisplayName("Quản trị viên (Admin) xem chi tiết bất kỳ đơn hàng nào thành công")
        void test_getOrderDetail_admin_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(
                    orderId, adminId, List.of("ROLE_ADMIN")
            );

            assertNotNull(result);
            assertEquals(orderId, result.id());
        }

        @Test
        @DisplayName("Người dùng lạ không phải chủ đơn và không phải Admin bị từ chối truy cập (403 FORBIDDEN)")
        void test_getOrderDetail_nonOwnerNonAdmin_throwsForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, strangerId, List.of("ROLE_BUYER"))
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderMapper, never()).toDetailResponse(any(Order.class));
        }

        @Test
        @DisplayName("Tra cứu đơn hàng không tồn tại ném ngoại lệ 404 NOT_FOUND")
        void test_getOrderDetail_orderNotFound_throwsNotFound() {
            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, buyerId, List.of("ROLE_BUYER"))
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }

        @Test
        @DisplayName("Xem chi tiết đơn hàng thông qua overload boolean isAdmin thành công")
        void test_getOrderDetail_booleanAdminOverload_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findWithItemsById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(orderId, adminId, true);

            assertNotNull(result);
            assertEquals(orderId, result.id());
        }
    }

    // =========================================================================
    // 3. NHÓM GET SELLER ORDERS
    // =========================================================================
    @Nested
    @DisplayName("getSellerOrders() - Lấy danh sách đơn hàng cho người bán (phạm vi Shop)")
    class GetSellerOrdersTests {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("Lấy danh sách đơn hàng của người bán mặc định giới hạn strictly theo sellerId")
        void test_getSellerOrders_defaultFilter_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findBySellerId(sellerId, pageable)).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            assertEquals(sellerId, response.getContent().get(0).sellerId());
            verify(orderRepository, times(1)).findBySellerId(sellerId, pageable);
        }

        @Test
        @DisplayName("Người bán lọc danh sách đơn theo trạng thái PROCESSING")
        void test_getSellerOrders_withStatusFilter_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PROCESSING);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findBySellerIdAndStatus(sellerId, OrderStatus.PROCESSING, pageable))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, OrderStatus.PROCESSING, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findBySellerIdAndStatus(sellerId, OrderStatus.PROCESSING, pageable);
        }

        @Test
        @DisplayName("Người bán lọc danh sách đơn theo khoảng thời gian tạo đơn")
        void test_getSellerOrders_withDateRangeFilter_success() {
            Instant start = Instant.now().minus(5, ChronoUnit.DAYS);
            Instant end = Instant.now();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findBySellerIdAndCreatedAtBetween(sellerId, start, end, pageable))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, null, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findBySellerIdAndCreatedAtBetween(sellerId, start, end, pageable);
        }

        @Test
        @DisplayName("Người bán lọc kết hợp cả trạng thái và khoảng thời gian")
        void test_getSellerOrders_withCombinedFilter_success() {
            Instant start = Instant.now().minus(2, ChronoUnit.DAYS);
            Instant end = Instant.now();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.SHIPPED);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findBySellerIdAndStatusAndCreatedAtBetween(
                    sellerId, OrderStatus.SHIPPED, start, end, pageable
            )).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, OrderStatus.SHIPPED, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findBySellerIdAndStatusAndCreatedAtBetween(
                    sellerId, OrderStatus.SHIPPED, start, end, pageable
            );
        }

        @Test
        @DisplayName("Người bán không có đơn hàng nào trả về Page rỗng an toàn")
        void test_getSellerOrders_emptyResult_returnsEmptyPageResponse() {
            when(orderRepository.findBySellerId(sellerId, pageable)).thenReturn(Page.empty(pageable));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertTrue(response.getContent().isEmpty());
            assertEquals(0, response.getTotalElements());
        }

        @Test
        @DisplayName("Khoảng thời gian không hợp lệ (startDate > endDate) ném ngoại lệ INVALID_PARAM_VALUE")
        void test_getSellerOrders_invalidDateRange_throwsException() {
            Instant start = Instant.now();
            Instant end = start.minus(2, ChronoUnit.DAYS);

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getSellerOrders(sellerId, null, start, end, pageable)
            );

            assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), ex.getCode());
        }

        @Test
        @DisplayName("Lọc đơn hàng người bán qua overload LocalDateTime chuyển đổi chính xác")
        void test_getSellerOrders_localDateTimeOverload_success() {
            LocalDateTime start = LocalDateTime.of(2026, 10, 1, 0, 0);
            LocalDateTime end = LocalDateTime.of(2026, 10, 29, 23, 59);
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findBySellerIdAndCreatedAtBetween(any(), any(), any(), any()))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, null, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
        }
    }

    // =========================================================================
    // 3B. NHÓM GET ADMIN ORDERS
    // =========================================================================
    @Nested
    @DisplayName("getAdminOrders() - Quản trị viên tra cứu toàn bộ đơn hàng hệ thống kèm phân trang")
    class GetAdminOrdersTests {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("Quản trị viên lấy toàn bộ danh sách đơn hàng toàn hệ thống")
        void test_getAdminOrders_allOrders_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findAll(pageable)).thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getAdminOrders(
                    null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findAll(pageable);
        }

        @Test
        @DisplayName("Quản trị viên lọc đơn hàng theo trạng thái và ngày tạo")
        void test_getAdminOrders_withStatusAndDateFilter_success() {
            Instant start = Instant.now().minus(7, ChronoUnit.DAYS);
            Instant end = Instant.now();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            Page<Order> mockPage = new PageImpl<>(List.of(order), pageable, 1);

            when(orderRepository.findByStatusAndCreatedAtBetween(eq(OrderStatus.DELIVERED), eq(start), eq(end), eq(pageable)))
                    .thenReturn(mockPage);
            when(orderMapper.toSummaryResponse(order)).thenReturn(buildMockSummaryResponse(order));

            Page<OrderSummaryResponse> response = orderService.getAdminOrders(
                    OrderStatus.DELIVERED, start, end, pageable
            );

            assertNotNull(response);
            assertEquals(1, response.getContent().size());
            verify(orderRepository, times(1)).findByStatusAndCreatedAtBetween(
                    OrderStatus.DELIVERED, start, end, pageable
            );
        }
    }

    // =========================================================================
    // 4. NHÓM UPDATE ORDER STATUS
    // =========================================================================
    @Nested
    @DisplayName("updateOrderStatus() - Cập nhật trạng thái đơn hàng và ghi log kiểm toán")
    class UpdateOrderStatusTests {

        @Test
        @DisplayName("Người bán chuyển trạng thái PAID sang PROCESSING khi đóng gói hàng")
        void test_updateOrderStatus_seller_paidToProcessing_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(
                    OrderStatus.PROCESSING, "Shop da xac nhan va dang dong goi hang"
            );
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PAID, OrderStatus.PROCESSING, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            OrderDetailResponse result = orderService.updateOrderStatus(orderId, request, sellerId, roles);

            assertNotNull(result);
            assertEquals(OrderStatus.PROCESSING, result.status());

            verify(orderRepository, times(1)).save(orderCaptor.capture());
            assertEquals(OrderStatus.PROCESSING, orderCaptor.getValue().getStatus());

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            OrderStatusHistory savedHistory = historyCaptor.getValue();
            assertEquals(OrderStatus.PAID, savedHistory.getFromStatus());
            assertEquals(OrderStatus.PROCESSING, savedHistory.getToStatus());
            assertTrue(savedHistory.getReason().startsWith("[SELLER]"));
            assertTrue(savedHistory.getReason().contains("Shop da xac nhan"));
        }

        @Test
        @DisplayName("Quản trị viên (Admin) cập nhật trạng thái đơn hàng kèm tiền tố [ADMIN] trong reason")
        void test_updateOrderStatus_admin_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(
                    OrderStatus.PAID, "Xac thuc giao dich thu cong qua sao ke ngan hang"
            );
            List<String> roles = List.of("ROLE_ADMIN");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.PAID, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            OrderDetailResponse result = orderService.updateOrderStatus(orderId, request, adminId, roles);

            assertNotNull(result);
            assertEquals(OrderStatus.PAID, result.status());

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            assertTrue(historyCaptor.getValue().getReason().startsWith("[ADMIN]"));
        }

        @Test
        @DisplayName("Tính lũy nghiệm (Idempotency): Đơn hàng đã ở trạng thái mục tiêu thì trả về ngay không tạo lịch sử thừa")
        void test_updateOrderStatus_idempotent_returnsCurrentStateWithoutMutation() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PROCESSING);
            UpdateOrderStatusRequest duplicateRequest = new UpdateOrderStatusRequest(
                    OrderStatus.PROCESSING, "Yeu cau cap nhat trung lap do timeout mang"
            );
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(buildMockDetailResponse(order));

            OrderDetailResponse result = orderService.updateOrderStatus(
                    orderId, duplicateRequest, sellerId, roles
            );

            assertNotNull(result);
            assertEquals(OrderStatus.PROCESSING, result.status());

            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderStatusHistoryRepository, never()).save(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Vi phạm quy trình chuyển trạng thái bị ném lỗi 409 ORDER_STATUS_INVALID")
        void test_updateOrderStatus_invalidTransition_throwsConflict() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            UpdateOrderStatusRequest illegalJumpRequest = new UpdateOrderStatusRequest(
                    OrderStatus.SHIPPED, "Co tinh nhay coc trang thai khong hop le"
            );
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doThrow(new BusinessException(
                    ErrorCode.ORDER_STATUS_INVALID,
                    "Khong the chuyen trang thai tu PENDING_PAYMENT sang SHIPPED"
            )).when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.SHIPPED, roles
            );

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.updateOrderStatus(orderId, illegalJumpRequest, sellerId, roles)
            );

            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            verify(orderStatusHistoryRepository, never()).save(any());
            verify(orderRepository, never()).save(any());
            assertEquals(OrderStatus.PENDING_PAYMENT, order.getStatus());
        }

        @Test
        @DisplayName("Người bán khác không sở hữu đơn hàng bị từ chối cập nhật trạng thái (403 FORBIDDEN)")
        void test_updateOrderStatus_nonOwnerSeller_throwsForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(
                    OrderStatus.PROCESSING, "Cap nhat boi nguoi ban la"
            );
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.updateOrderStatus(orderId, request, strangerId, roles)
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cập nhật trạng thái đơn hàng không tồn tại ném lỗi 404 NOT_FOUND")
        void test_updateOrderStatus_orderNotFound_throwsNotFound() {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(
                    OrderStatus.PROCESSING, "Don hang khong ton tai"
            );
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.updateOrderStatus(orderId, request, sellerId, List.of("ROLE_SELLER"))
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }
    }

    // =========================================================================
    // 5. NHÓM CANCEL ORDER
    // =========================================================================
    @Nested
    @DisplayName("cancelOrder() - Hủy đơn hàng và kiểm tra điều kiện hủy hợp lệ")
    class CancelOrderTests {

        @Test
        @DisplayName("Người mua hủy đơn hàng đang chờ thanh toán (PENDING_PAYMENT) thành công")
        void test_cancelOrder_buyer_pendingPayment_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            CancelOrderRequest request = new CancelOrderRequest("Toi muon thay doi dia chi nhan hang");
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            OrderDetailResponse result = orderService.cancelOrder(orderId, request, buyerId, roles);

            assertNotNull(result);
            assertEquals(OrderStatus.CANCELLED, result.status());

            verify(orderRepository, times(1)).save(orderCaptor.capture());
            assertEquals(OrderStatus.CANCELLED, orderCaptor.getValue().getStatus());

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            OrderStatusHistory history = historyCaptor.getValue();
            assertEquals(OrderStatus.PENDING_PAYMENT, history.getFromStatus());
            assertEquals(OrderStatus.CANCELLED, history.getToStatus());
            assertTrue(history.getReason().startsWith("[BUYER]"));
            assertTrue(history.getReason().contains("thay doi dia chi"));
        }

        /** Không có provider refund thì hủy PAID phải giữ nguyên tiền/trạng thái và không ghi history giả. */
        @Test
        void test_cancelOrder_admin_paidRequiresRefundProvider() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            var error = assertThrows(BusinessException.class, () -> orderService.cancelOrder(orderId,
                    new CancelOrderRequest("Yêu cầu hủy"), adminId, List.of("ROLE_ADMIN")));
            assertEquals(ErrorCode.ORDER_CANNOT_CANCEL.name(), error.getCode());
            assertEquals(OrderStatus.PAID, order.getStatus());
            verify(orderRepository, never()).save(any());
            verify(orderStatusHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Người mua hủy đơn khi hàng đã xuất kho hoặc vận chuyển (SHIPPED) bị từ chối với ORDER_CANNOT_CANCEL")
        void test_cancelOrder_shippedOrder_throwsCannotCancelOrInvalid() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.SHIPPED);
            CancelOrderRequest request = new CancelOrderRequest("Muon huy vi giao hang cham");
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.cancelOrder(orderId, request, buyerId, roles)
            );

            assertEquals(ErrorCode.ORDER_CANNOT_CANCEL.name(), ex.getCode());
            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderStatusHistoryRepository, never()).save(any());
            verify(orderRepository, never()).save(any());
            assertEquals(OrderStatus.SHIPPED, order.getStatus());
        }

        @Test
        @DisplayName("Người dùng khác cố tình gửi request hủy đơn không thuộc sở hữu bị cấm (403 FORBIDDEN)")
        void test_cancelOrder_otherBuyer_throwsForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            CancelOrderRequest request = new CancelOrderRequest("Co tinh huy don hang cua nguoi khac");
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.cancelOrder(orderId, request, strangerId, roles)
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Tính lũy nghiệm khi đơn hàng đã ở trạng thái CANCELLED trả về kết quả an toàn")
        void test_cancelOrder_alreadyCancelled_idempotentSuccess() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.CANCELLED);
            CancelOrderRequest request = new CancelOrderRequest("Yeu cau huy lap lai do mang lag");
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(buildMockDetailResponse(order));

            OrderDetailResponse result = orderService.cancelOrder(orderId, request, buyerId, roles);

            assertNotNull(result);
            assertEquals(OrderStatus.CANCELLED, result.status());

            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderStatusHistoryRepository, never()).save(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Tính lũy nghiệm khi đơn hàng đã ở trạng thái REFUNDED trả về kết quả an toàn")
        void test_cancelOrder_alreadyRefunded_idempotentSuccess() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.REFUNDED);
            CancelOrderRequest request = new CancelOrderRequest("Yeu cau huy lap lai do mang lag");
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(buildMockDetailResponse(order));

            OrderDetailResponse result = orderService.cancelOrder(orderId, request, buyerId, roles);

            assertNotNull(result);
            assertEquals(OrderStatus.REFUNDED, result.status());

            verify(orderStatusStateMachine, never()).validateTransition(any(), any(), any(Collection.class));
            verify(orderStatusHistoryRepository, never()).save(any());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Hủy đơn hàng không tồn tại ném ngoại lệ 404 NOT_FOUND")
        void test_cancelOrder_orderNotFound_throwsNotFound() {
            CancelOrderRequest request = new CancelOrderRequest("Don khong ton tai");
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.cancelOrder(orderId, request, buyerId, List.of("ROLE_BUYER"))
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }
    }

    /**
     * Bộ kiểm thử chức năng bàn giao khởi tạo đơn hàng từ phiên đấu giá thành công (DoD KH-S2-2 / SRS §3.5.3).
     *
     * Chức năng:
     * - Tạo đơn hàng loại AUCTION với trạng thái PENDING_PAYMENT, hạn thanh toán 24h.
     * - Lưu snapshot tên sản phẩm và mã SKU từ Catalog Service, giá trúng phiên làm đơn giá.
     * - Đảm bảo tính lũy nghiệm (Idempotency): Nếu gọi lại với cùng auction_id, trả về đơn hàng đã tạo trước đó.
     * - Xác thực tính toàn vẹn: Không cho phép winner trùng sellerId, finalPrice phải > 0.
     */
    @Nested
    @DisplayName("CreateAuctionOrder Tests - Khởi tạo đơn hàng từ phiên đấu giá")
    class CreateAuctionOrderTest {

        private final UUID auctionId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        private final UUID skuId = UUID.fromString("00000000-0000-0000-0000-000000000088");
        private final BigDecimal finalPrice = new BigDecimal("1500000.00");
        private final Instant paymentDeadline = Instant.now().plus(24, ChronoUnit.HOURS);

        @Test
        @DisplayName("Tạo đơn hàng đấu giá thành công với snapshot Catalog và hạn thanh toán 24h")
        void test_createAuctionOrder_success() {
            // Đầu vào: Yêu cầu khởi tạo đơn hàng đấu giá hợp lệ
            CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                    auctionId,
                    buyerId,
                    sellerId,
                    skuId,
                    finalPrice,
                    paymentDeadline
            );

            // Giả lập Catalog Service trả về thông tin SKU snapshot
            SkuInfo mockSkuInfo = new SkuInfo(
                    skuId, "SKU-AUCTION-VINTAGE", "Dong ho Vintage 1980", "thumb.jpg",
                    new BigDecimal("1000000.00"), 1, true, sellerId
            );
            when(orderRepository.findByAuctionId(auctionId)).thenReturn(Optional.empty());
            when(catalogClient.getSkuInfo(skuId)).thenReturn(mockSkuInfo);
            when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
                Order o = invocation.getArgument(0);
                o.setId(orderId);
                return o;
            });

            // Thực thi
            AuctionOrderResponse response = orderService.createAuctionOrder(request);

            // Kiểm tra kết quả
            assertNotNull(response);
            assertEquals(orderId, response.orderId());
            assertEquals(auctionId, response.auctionId());
            assertEquals(buyerId, response.buyerId());
            assertEquals(sellerId, response.sellerId());
            assertEquals(OrderStatus.PENDING_PAYMENT.name(), response.status());
            assertEquals(finalPrice, response.total());
            assertNotNull(response.paymentDeadline());
            assertTrue(response.paymentDeadline().isAfter(Instant.now()));

            // Xác minh thực thể Order được lưu với đúng cấu hình
            verify(orderRepository).save(orderCaptor.capture());
            Order savedOrder = orderCaptor.getValue();
            assertEquals(OrderSourceType.AUCTION, savedOrder.getSourceType());
            assertEquals(auctionId.toString(), savedOrder.getIdempotencyKey());
            assertEquals(auctionId, savedOrder.getAuctionId());
            assertEquals(OrderStatus.PENDING_PAYMENT, savedOrder.getStatus());
            // Giá settlement là dự kiến bất biến; official chỉ được chốt sau IPN thành công.
            org.junit.jupiter.api.Assertions.assertNull(savedOrder.getSubtotal());
            org.junit.jupiter.api.Assertions.assertNull(savedOrder.getTotal());
            assertEquals(finalPrice, savedOrder.getEstimatedSubtotal());
            assertEquals(BigDecimal.ZERO, savedOrder.getEstimatedShippingFee());
            assertEquals(finalPrice, savedOrder.getEstimatedTotal());
            assertEquals(paymentDeadline, savedOrder.getPaymentDeadline());

            // Xác minh OrderItem snapshot từ Catalog và đơn giá là giá thắng cuộc
            assertEquals(1, savedOrder.getItems().size());
            OrderItem item = savedOrder.getItems().getFirst();
            assertEquals(skuId, item.getSkuId());
            assertEquals("Dong ho Vintage 1980", item.getProductName());
            assertEquals("SKU-AUCTION-VINTAGE", item.getSkuCode());
            org.junit.jupiter.api.Assertions.assertNull(item.getUnitPrice());
            assertEquals(finalPrice, item.getEstimatedUnitPrice());
            assertEquals(1, item.getQuantity());

            // Xác minh lịch sử trạng thái ban đầu PENDING_PAYMENT được ghi nhận trong đơn hàng
            assertEquals(1, savedOrder.getStatusHistory().size());
            OrderStatusHistory history = savedOrder.getStatusHistory().getFirst();
            assertEquals(OrderStatus.PENDING_PAYMENT, history.getToStatus());
        }

        @Test
        @DisplayName("Tính lũy nghiệm: Đã tồn tại đơn hàng với auction_id thì trả về đơn cũ mà không tạo mới")
        void test_createAuctionOrder_idempotent_returnsExistingOrder() {
            // Chuẩn bị đơn hàng đã được tạo trước đó
            Order existingOrder = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            existingOrder.setSourceType(OrderSourceType.AUCTION);
            existingOrder.setAuctionId(auctionId);
            existingOrder.setTotal(finalPrice);
            existingOrder.setCreatedAt(paymentDeadline.minus(24, ChronoUnit.HOURS));

            CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                    auctionId,
                    buyerId,
                    sellerId,
                    skuId,
                    finalPrice,
                    paymentDeadline
            );

            when(orderRepository.findByAuctionId(auctionId)).thenReturn(Optional.of(existingOrder));

            // Thực thi
            AuctionOrderResponse response = orderService.createAuctionOrder(request);

            // Kiểm tra kết quả khớp với đơn hàng hiện hữu
            assertNotNull(response);
            assertEquals(orderId, response.orderId());
            assertEquals(auctionId, response.auctionId());
            assertEquals(buyerId, response.buyerId());

            // Đảm bảo không gọi repository lưu mới hay ghi đè
            verify(orderRepository, never()).save(any(Order.class));
            verify(orderStatusHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("Người thắng trùng với người bán ném ngoại lệ ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT")
        void test_createAuctionOrder_winnerIsSeller_throwsException() {
            CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                    auctionId,
                    sellerId, // winner = seller
                    sellerId,
                    skuId,
                    finalPrice,
                    paymentDeadline
            );

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.createAuctionOrder(request)
            );

            assertEquals(ErrorCode.ORDER_SELLER_CANNOT_BUY_OWN_PRODUCT.name(), ex.getCode());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Giá thắng cuộc không hợp lệ (nhỏ hơn hoặc bằng 0) ném ngoại lệ INVALID_PARAM_VALUE")
        void test_createAuctionOrder_invalidPrice_throwsException() {
            CreateAuctionOrderRequest request = new CreateAuctionOrderRequest(
                    auctionId,
                    buyerId,
                    sellerId,
                    skuId,
                    BigDecimal.ZERO, // giá <= 0
                    paymentDeadline
            );

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.createAuctionOrder(request)
            );

            assertEquals(ErrorCode.INVALID_PARAM_VALUE.name(), ex.getCode());
            verify(orderRepository, never()).save(any());
        }

        @Test
        @DisplayName("Gắn danh sách reservationIds thành công và idempotent không ghi trùng")
        void test_attachReservations_successAndIdempotent() {
            UUID res1 = UUID.randomUUID();
            UUID res2 = UUID.randomUUID();
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);

            // Attach khóa cùng Order với payment để replay đồng thời không insert trùng phiếu.
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            // Lần 1: Gắn 2 phiếu
            orderService.attachReservations(orderId, List.of(res1, res2));
            assertEquals(2, order.getReservationIds().size());
            assertTrue(order.getReservationIds().contains(res1));
            assertTrue(order.getReservationIds().contains(res2));

            // Lần 2: Gắn lại cùng danh sách (idempotent)
            orderService.attachReservations(orderId, List.of(res1, res2));
            assertEquals(2, order.getReservationIds().size());
        }

        @Test
        @DisplayName("Gắn reservationIds cho đơn không tồn tại ném ngoại lệ ORDER_NOT_FOUND")
        void test_attachReservations_notFound_throwsException() {
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.attachReservations(orderId, List.of(UUID.randomUUID()))
            );
            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }
    }

    /**
     * Nhóm kiểm thử giải quyết tranh chấp ký quỹ của Quản trị viên và tiếp nhận khiếu nại của Người mua.
     */
    @Nested
    @DisplayName("Xử lý tranh chấp ký quỹ đơn hàng (resolveDispute & submitDispute)")
    class DisputeResolutionServiceTests {

        /**
         * Mục đích: Admin hoàn tiền (refunded) thành công cho đơn đã giao hàng.
         * Đầu vào: orderId, resolution = 'refunded', note giải trình.
         * Kết quả: Order status chuyển sang REFUNDED, OrderStatusHistory được ghi nhận.
         */
        @Test
        @DisplayName("Admin giải quyết tranh chấp hoàn tiền (refunded) -> Đơn sang REFUNDED và ghi audit log")
        void test_resolveDispute_refunded_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            OrderDetailResponse mockResponse = buildMockDetailResponse(order);
            when(orderMapper.toDetailResponse(any(Order.class))).thenReturn(mockResponse);

            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("refunded", "Sản phẩm bị nứt vỡ");
            OrderDetailResponse result = orderService.resolveDispute(orderId, request, adminId, List.of("ROLE_ADMIN"));

            assertNotNull(result);
            assertEquals(OrderStatus.REFUNDED, order.getStatus());
            verify(orderRepository).save(order);
            verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        }

        /**
         * Mục đích: Admin giải ngân (released) cho người bán -> Đơn chuyển sang COMPLETED.
         */
        @Test
        @DisplayName("Admin giải quyết tranh chấp giải ngân (released) -> Đơn sang COMPLETED")
        void test_resolveDispute_released_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));

            OrderDetailResponse mockResponse = buildMockDetailResponse(order);
            when(orderMapper.toDetailResponse(any(Order.class))).thenReturn(mockResponse);

            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("released", "Chứng từ giao hàng hợp lệ");
            OrderDetailResponse result = orderService.resolveDispute(orderId, request, adminId, List.of("ADMIN"));

            assertNotNull(result);
            assertEquals(OrderStatus.COMPLETED, order.getStatus());
            verify(orderRepository).save(order);
            verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        }

        /**
         * Mục đích: Người dùng không có vai trò ADMIN gọi resolveDispute bị từ chối 403.
         */
        @Test
        @DisplayName("Non-Admin gọi resolveDispute -> Ném AUTH_FORBIDDEN")
        void test_resolveDispute_nonAdmin_throwsForbidden() {
            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("refunded", "Cố tình gọi");

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.resolveDispute(orderId, request, buyerId, List.of("ROLE_BUYER"))
            );
            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderRepository, never()).findForPayment(any());
        }

        /**
         * Mục đích: Từ chối xử lý tranh chấp khi đơn hàng chưa thanh toán (PENDING_PAYMENT).
         */
        @Test
        @DisplayName("Đơn hàng chưa thanh toán (PENDING_PAYMENT) -> Ném ORDER_STATUS_INVALID")
        void test_resolveDispute_pendingPayment_throwsInvalid() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            ResolveOrderDisputeRequest request = new ResolveOrderDisputeRequest("refunded", "Chưa thanh toán");

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.resolveDispute(orderId, request, adminId, List.of("ROLE_ADMIN"))
            );
            assertEquals(ErrorCode.ORDER_STATUS_INVALID.name(), ex.getCode());
            verify(orderRepository, never()).save(any());
        }

        /**
         * Mục đích: Người mua gửi khiếu nại đơn hàng của chính mình -> Ghi audit log [DISPUTE_FILED].
         */
        @Test
        @DisplayName("Người mua gửi khiếu nại đơn hàng của mình -> Ghi vết kiểm toán thành công")
        void test_submitDispute_buyer_success() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            OrderDetailResponse mockResponse = buildMockDetailResponse(order);
            when(orderMapper.toDetailResponse(any(Order.class))).thenReturn(mockResponse);

            SubmitOrderDisputeRequest request = new SubmitOrderDisputeRequest("Giao sai phân loại hàng");
            OrderDetailResponse result = orderService.submitDispute(orderId, request, buyerId, List.of("ROLE_BUYER"));

            assertNotNull(result);
            verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        }

        /**
         * Mục đích: Người dùng khác không phải chủ đơn gửi khiếu nại -> Ném AUTH_FORBIDDEN.
         */
        @Test
        @DisplayName("Người khác gửi khiếu nại đơn hàng -> Ném AUTH_FORBIDDEN")
        void test_submitDispute_otherUser_throwsForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.DELIVERED);
            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));

            UUID otherUserId = UUID.randomUUID();
            SubmitOrderDisputeRequest request = new SubmitOrderDisputeRequest("Khiếu nại giùm");

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.submitDispute(orderId, request, otherUserId, List.of("ROLE_BUYER"))
            );
            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderStatusHistoryRepository, never()).save(any());
        }
    }
}
