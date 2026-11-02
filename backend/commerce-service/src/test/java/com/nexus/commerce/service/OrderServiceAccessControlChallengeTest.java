package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CancelOrderRequest;
import com.nexus.commerce.dto.request.UpdateOrderStatusRequest;
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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * Bộ kiểm thử đối kháng (Adversarial Challenge Test) cho Milestone 2 - Issue HG-S1-4.
 *
 * <p>Mục đích kiểm thử:
 * Kiểm chứng thực nghiệm các khía cạnh bảo mật kiểm soát truy cập và điều kiện biên:
 * <ol>
 *   <li>Probe 1: Kiểm soát truy cập trái phép xem chi tiết (403 AUTH_FORBIDDEN vs quyền hợp lệ).</li>
 *   <li>Probe 2: Xử lý đơn hàng không tồn tại (404 ORDER_NOT_FOUND trên mọi API thao tác).</li>
 *   <li>Probe 3: Biên độ dài lý do reason (> 500 ký tự cắt an toàn, tránh tràn cột DB varchar(500)).</li>
 *   <li>Probe 4: Xử lý trang truy vấn rỗng (PageResponse an toàn, không ném NullPointerException).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceAccessControlChallengeTest - Bộ kiểm thử đối kháng phân quyền và điều kiện biên")
class OrderServiceAccessControlChallengeTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OrderStatusStateMachine orderStatusStateMachine;

    @Mock
    private OrderMapper orderMapper;

    @Captor
    private ArgumentCaptor<OrderStatusHistory> historyCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private OrderServiceImpl orderService;
    private Validator validator;

    private final UUID orderId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private final UUID buyerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID sellerId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final UUID strangerId = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @BeforeEach
    void setUp() {
        orderService = new OrderServiceImpl(
                orderRepository,
                orderStatusHistoryRepository,
                orderStatusStateMachine,
                orderMapper, org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class)
        );

        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Order buildMockOrder(UUID id, UUID buyer, UUID seller, OrderStatus status) {
        Order order = Order.builder()
                .id(id)
                .orderNo("ORD-CHALLENGE-20261029-001")
                .buyerId(buyer)
                .sellerId(seller)
                .sourceType(OrderSourceType.REGULAR)
                .status(status)
                .subtotal(new BigDecimal("200000.00"))
                .shippingFee(new BigDecimal("30000.00"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("230000.00"))
                .shippingAddress("123 Duong Le Duan, Quan 1, TP. Ho Chi Minh")
                .createdAt(Instant.now())
                .items(new ArrayList<>())
                .statusHistory(new ArrayList<>())
                .build();

        OrderItem item = OrderItem.builder()
                .id(UUID.randomUUID())
                .order(order)
                .skuId(UUID.randomUUID())
                .productName("Ao thun Nexus")
                .skuCode("NEX-TSHIRT-XL")
                .unitPrice(new BigDecimal("100000.00"))
                .quantity(2)
                .build();
        order.addItem(item);
        return order;
    }

    private OrderDetailResponse buildMockDetailResponse(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems().stream()
                .map(i -> OrderItemResponse.builder()
                        .id(i.getId())
                        .skuId(i.getSkuId())
                        .productName(i.getProductName())
                        .skuCode(i.getSkuCode())
                        .unitPrice(i.getUnitPrice())
                        .quantity(i.getQuantity())
                        .subtotal(i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                        .build())
                .toList();

        List<OrderStatusHistoryResponse> historyResponses = List.of(
                OrderStatusHistoryResponse.builder()
                        .id(UUID.randomUUID())
                        .fromStatus(null)
                        .toStatus(order.getStatus())
                        .actor("SYSTEM")
                        .reason("Tao don hang ban dau")
                        .changedAt(order.getCreatedAt())
                        .build()
        );

        return OrderDetailResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .buyerId(order.getBuyerId())
                .sellerId(order.getSellerId())
                .sourceType(order.getSourceType())
                .status(order.getStatus())
                .subtotal(order.getSubtotal())
                .shippingFee(order.getShippingFee())
                .discount(order.getDiscount())
                .total(order.getTotal())
                .shippingAddress(order.getShippingAddress())
                .createdAt(order.getCreatedAt())
                .items(itemResponses)
                .statusHistory(historyResponses)
                .build();
    }

    // =========================================================================
    // PROBE 1: UNAUTHORIZED DETAIL VIEW (403 AUTH_FORBIDDEN VS LEGITIMATE ROLES)
    // =========================================================================
    @Nested
    @DisplayName("Probe 1: Kiểm soát phân quyền xem chi tiết đơn hàng (getOrderDetail)")
    class ProbeUnauthorizedDetailView {

        @Test
        @DisplayName("Người mua lạ (Authenticated Stranger với ROLE_BUYER) bị từ chối với 403 AUTH_FORBIDDEN")
        void test_strangerBuyer_throwsAuthForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, strangerId, List.of("ROLE_BUYER"))
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderMapper, never()).toDetailResponse(any(Order.class));
        }

        @Test
        @DisplayName("Người bán lạ (Authenticated Stranger với ROLE_SELLER) bị từ chối với 403 AUTH_FORBIDDEN")
        void test_strangerSeller_throwsAuthForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, strangerId, List.of("ROLE_SELLER"))
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
            verify(orderMapper, never()).toDetailResponse(any(Order.class));
        }

        @Test
        @DisplayName("Người dùng với vai trò không đủ quyền (ROLE_SUPPORT) không phải Admin bị từ chối 403")
        void test_arbitraryRoleWithoutAdmin_throwsAuthForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PROCESSING);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, strangerId, List.of("ROLE_SUPPORT"))
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
        }

        @Test
        @DisplayName("Người dùng không truyền ID (null) và vai trò rỗng bị từ chối với 403 AUTH_FORBIDDEN")
        void test_nullUserAndEmptyRoles_throwsAuthForbidden() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(orderId, null, List.of())
            );

            assertEquals(ErrorCode.AUTH_FORBIDDEN.name(), ex.getCode());
        }

        @Test
        @DisplayName("Chủ sở hữu đơn hàng (Buyer Owner) được xem chi tiết thành công")
        void test_legitimateBuyerOwner_accessGranted() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(orderId, buyerId, List.of("ROLE_BUYER"));

            assertNotNull(result);
            assertEquals(orderId, result.id());
            assertEquals(buyerId, result.buyerId());
        }

        @Test
        @DisplayName("Chủ gian hàng (Seller Owner) được xem chi tiết thành công")
        void test_legitimateSellerOwner_accessGranted() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(orderId, sellerId, List.of("ROLE_SELLER"));

            assertNotNull(result);
            assertEquals(orderId, result.id());
            assertEquals(sellerId, result.sellerId());
        }

        @Test
        @DisplayName("Quản trị viên với vai trò ROLE_ADMIN xem chi tiết thành công bất kể ID người dùng")
        void test_adminWithRoleAdmin_accessGranted() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(orderId, adminId, List.of("ROLE_ADMIN"));

            assertNotNull(result);
            assertEquals(orderId, result.id());
        }

        @Test
        @DisplayName("Quản trị viên với tên vai trò không có tiền tố ROLE_ (ADMIN) vẫn được chấp nhận hợp lệ")
        void test_adminWithoutPrefix_accessGranted() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            OrderDetailResponse detailResponse = buildMockDetailResponse(order);

            when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
            when(orderMapper.toDetailResponse(order)).thenReturn(detailResponse);

            OrderDetailResponse result = orderService.getOrderDetail(orderId, adminId, List.of("ADMIN"));

            assertNotNull(result);
            assertEquals(orderId, result.id());
        }
    }

    // =========================================================================
    // PROBE 2: NONEXISTENT ORDER (404 ORDER_NOT_FOUND)
    // =========================================================================
    @Nested
    @DisplayName("Probe 2: Xử lý đơn hàng không tồn tại (404 ORDER_NOT_FOUND)")
    class ProbeNonexistentOrder {

        private final UUID nonExistentOrderId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        @Test
        @DisplayName("getOrderDetail với ID không tồn tại ném 404 ORDER_NOT_FOUND")
        void test_getOrderDetail_nonExistentOrder_throwsOrderNotFound() {
            when(orderRepository.findById(nonExistentOrderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.getOrderDetail(nonExistentOrderId, buyerId, List.of("ROLE_BUYER"))
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }

        @Test
        @DisplayName("updateOrderStatus với ID không tồn tại ném 404 ORDER_NOT_FOUND")
        void test_updateOrderStatus_nonExistentOrder_throwsOrderNotFound() {
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(
                    OrderStatus.PROCESSING, "Xac nhan dong goi"
            );
            when(orderRepository.findForPayment(nonExistentOrderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.updateOrderStatus(
                            nonExistentOrderId, request, sellerId, List.of("ROLE_SELLER")
                    )
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }

        @Test
        @DisplayName("cancelOrder với ID không tồn tại ném 404 ORDER_NOT_FOUND")
        void test_cancelOrder_nonExistentOrder_throwsOrderNotFound() {
            CancelOrderRequest request = new CancelOrderRequest("Khong co nhu cau nua");
            when(orderRepository.findForPayment(nonExistentOrderId)).thenReturn(Optional.empty());

            BusinessException ex = assertThrows(
                    BusinessException.class,
                    () -> orderService.cancelOrder(
                            nonExistentOrderId, request, buyerId, List.of("ROLE_BUYER")
                    )
            );

            assertEquals(ErrorCode.ORDER_NOT_FOUND.name(), ex.getCode());
        }
    }

    // =========================================================================
    // PROBE 3: REASON FIELD BOUNDARY (> 500 CHARS TRUNCATION AND DTO VALIDATION)
    // =========================================================================
    @Nested
    @DisplayName("Probe 3: Giới hạn độ dài lý do reason (> 500 ký tự cắt an toàn, phòng tràn cột DB)")
    class ProbeReasonFieldBoundary {

        @Test
        @DisplayName("updateOrderStatus với reason 600 ký tự được cắt an toàn tối đa 500 ký tự trong audit log")
        void test_updateOrderStatus_reasonExceeding500Chars_safelyTruncated() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            String longReason = "A".repeat(600);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, longReason);
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PAID, OrderStatus.PROCESSING, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            orderService.updateOrderStatus(orderId, request, sellerId, roles);

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            OrderStatusHistory history = historyCaptor.getValue();
            assertNotNull(history.getReason());
            assertTrue(history.getReason().length() <= 500, "Độ dài reason trong DB không được vượt quá 500 ký tự");
            assertEquals(500, history.getReason().length(), "Khi chuỗi dài vượt quá 500 ký tự thì phải cắt chính xác 500");
            assertTrue(history.getReason().startsWith("[SELLER]"));
        }

        @Test
        @DisplayName("cancelOrder với reason 1000 ký tự được cắt an toàn tối đa 500 ký tự trong audit log")
        void test_cancelOrder_reasonExceeding500Chars_safelyTruncated() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PENDING_PAYMENT);
            String hugeReason = "Ly do huy cuc ky dai: " + "X".repeat(1000);
            CancelOrderRequest request = new CancelOrderRequest(hugeReason);
            List<String> roles = List.of("ROLE_BUYER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            orderService.cancelOrder(orderId, request, buyerId, roles);

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            OrderStatusHistory history = historyCaptor.getValue();
            assertNotNull(history.getReason());
            assertTrue(history.getReason().length() <= 500, "Độ dài reason không được vượt quá 500 ký tự");
            assertEquals(500, history.getReason().length());
            assertTrue(history.getReason().startsWith("[BUYER]"));
        }

        @Test
        @DisplayName("updateOrderStatus với reason là null được gán giá trị mặc định an toàn")
        void test_updateOrderStatus_nullReason_defaultsSafely() {
            Order order = buildMockOrder(orderId, buyerId, sellerId, OrderStatus.PAID);
            UpdateOrderStatusRequest request = new UpdateOrderStatusRequest(OrderStatus.PROCESSING, null);
            List<String> roles = List.of("ROLE_SELLER");

            when(orderRepository.findForPayment(orderId)).thenReturn(Optional.of(order));
            doNothing().when(orderStatusStateMachine).validateTransition(
                    OrderStatus.PAID, OrderStatus.PROCESSING, roles
            );
            when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
            when(orderMapper.toDetailResponse(any(Order.class))).thenAnswer(inv -> buildMockDetailResponse(inv.getArgument(0)));

            orderService.updateOrderStatus(orderId, request, sellerId, roles);

            verify(orderStatusHistoryRepository, times(1)).save(historyCaptor.capture());
            OrderStatusHistory history = historyCaptor.getValue();
            assertEquals("[SELLER] Chuyển trạng thái sang PROCESSING", history.getReason());
        }

        @Test
        @DisplayName("Validation tầng DTO: UpdateOrderStatusRequest từ chối reason > 500 ký tự qua @Size")
        void test_dtoValidation_updateOrderStatusRequest_rejectsOver500Chars() {
            UpdateOrderStatusRequest validReq = new UpdateOrderStatusRequest(OrderStatus.PAID, "A".repeat(500));
            Set<ConstraintViolation<UpdateOrderStatusRequest>> validViolations = validator.validate(validReq);
            assertTrue(validViolations.isEmpty(), "Reason 500 ký tự phải hợp lệ");

            UpdateOrderStatusRequest invalidReq = new UpdateOrderStatusRequest(OrderStatus.PAID, "A".repeat(501));
            Set<ConstraintViolation<UpdateOrderStatusRequest>> violations = validator.validate(invalidReq);
            assertFalse(violations.isEmpty(), "Reason 501 ký tự phải bị Bean Validation từ chối");
            assertEquals(1, violations.size());
            assertTrue(violations.iterator().next().getMessage().contains("không được vượt quá 500 ký tự"));
        }

        @Test
        @DisplayName("Validation tầng DTO: CancelOrderRequest từ chối reason > 500 ký tự và bắt buộc @NotBlank")
        void test_dtoValidation_cancelOrderRequest_rejectsOver500CharsAndBlank() {
            CancelOrderRequest validReq = new CancelOrderRequest("A".repeat(500));
            Set<ConstraintViolation<CancelOrderRequest>> validViolations = validator.validate(validReq);
            assertTrue(validViolations.isEmpty(), "Reason 500 ký tự phải hợp lệ");

            CancelOrderRequest invalidLenReq = new CancelOrderRequest("A".repeat(501));
            Set<ConstraintViolation<CancelOrderRequest>> lenViolations = validator.validate(invalidLenReq);
            assertFalse(lenViolations.isEmpty(), "Reason 501 ký tự phải bị Bean Validation từ chối");

            CancelOrderRequest blankReq = new CancelOrderRequest("   ");
            Set<ConstraintViolation<CancelOrderRequest>> blankViolations = validator.validate(blankReq);
            assertFalse(blankViolations.isEmpty(), "Reason trống phải bị @NotBlank từ chối");
        }
    }

    // =========================================================================
    // PROBE 4: EMPTY QUERY RESULTS (PAGERESPONSE SAFE HANDLING)
    // =========================================================================
    @Nested
    @DisplayName("Probe 4: Xử lý danh sách truy vấn rỗng (Page an toàn, không ném NullPointerException)")
    class ProbeEmptyQueryResults {

        private final Pageable pageable = PageRequest.of(0, 10);

        @Test
        @DisplayName("getBuyerOrders khi người mua không có đơn hàng trả về Page rỗng chuẩn xác")
        void test_getBuyerOrders_emptyPage_returnsSafeEmptyPageResponse() {
            when(orderRepository.findByBuyerId(buyerId, pageable)).thenReturn(Page.empty(pageable));

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertNotNull(response.getContent());
            assertTrue(response.getContent().isEmpty());
            assertEquals(0, response.getNumber());
            assertEquals(10, response.getSize());
            assertEquals(0L, response.getTotalElements());
            assertEquals(0, response.getTotalPages());
            assertTrue(response.isFirst());
            assertTrue(response.isLast());
        }

        @Test
        @DisplayName("getSellerOrders khi người bán không có đơn hàng trả về Page rỗng chuẩn xác")
        void test_getSellerOrders_emptyPage_returnsSafeEmptyPageResponse() {
            when(orderRepository.findBySellerId(sellerId, pageable)).thenReturn(Page.empty(pageable));

            Page<OrderSummaryResponse> response = orderService.getSellerOrders(
                    sellerId, null, (Instant) null, null, pageable
            );

            assertNotNull(response);
            assertNotNull(response.getContent());
            assertTrue(response.getContent().isEmpty());
            assertEquals(0L, response.getTotalElements());
            assertEquals(0, response.getTotalPages());
        }

        @Test
        @DisplayName("Truy vấn với số trang vượt quá biên (Out of bounds page) không gây NullPointerException")
        void test_queryOutOfBoundsPage_returnsSafeEmptyResponse() {
            Pageable outOfBoundsPageable = PageRequest.of(99, 20);
            Page<Order> emptyPage = new PageImpl<>(List.of(), outOfBoundsPageable, 0);

            when(orderRepository.findByBuyerId(buyerId, outOfBoundsPageable)).thenReturn(emptyPage);

            Page<OrderSummaryResponse> response = orderService.getBuyerOrders(
                    buyerId, null, (Instant) null, null, outOfBoundsPageable
            );

            assertNotNull(response);
            assertNotNull(response.getContent());
            assertTrue(response.getContent().isEmpty());
            assertEquals(99, response.getNumber());
            assertEquals(20, response.getSize());
            assertEquals(0L, response.getTotalElements());
            assertEquals(0, response.getTotalPages());
        }
    }
}
