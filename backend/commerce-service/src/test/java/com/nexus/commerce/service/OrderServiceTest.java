package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.dto.ReservationResponse;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.PageResponse;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.exception.BusinessException;
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.repository.OrderItemRepository;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.impl.OrderServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

/**
 * Kiểm thử đơn vị cho OrderServiceImpl:
 * - Tạo đơn hàng từ Checkout, chống trùng lặp Idempotency-Key.
 * - Snapshot dữ liệu sản phẩm và gọi giữ kho InventoryClient.
 * - Xác thực chuyển đổi trạng thái đơn hàng (Order State Machine) và ghi nhận Audit Trail.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private CartRepository cartRepository;

    @Mock
    private CartItemRepository cartItemRepository;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private InventoryClient inventoryClient;

    private OrderMapper orderMapper;
    private OrderServiceImpl orderService;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID skuId = UUID.randomUUID();
    private final UUID cartItemId = UUID.randomUUID();
    private final String idempotencyKey = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        orderMapper = new OrderMapper();
        orderService = new OrderServiceImpl(
                orderRepository,
                orderItemRepository,
                orderStatusHistoryRepository,
                cartRepository,
                cartItemRepository,
                catalogClient,
                inventoryClient,
                orderMapper
        );
    }

    @Test
    @DisplayName("Tạo đơn hàng thành công khi đầy đủ thông tin hợp lệ")
    void createOrder_success() {
        // Mock giỏ hàng active
        Cart cart = Cart.builder()
                .id(UUID.randomUUID())
                .userId(buyerId)
                .status(CartStatus.ACTIVE)
                .items(new ArrayList<>())
                .build();

        CartItem cartItem = CartItem.builder()
                .id(cartItemId)
                .cart(cart)
                .skuId(skuId)
                .quantity(2)
                .unitPrice(new BigDecimal("2900000.00"))
                .build();
        cart.getItems().add(cartItem);

        when(cartRepository.findByUserIdAndStatus(buyerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());

        SkuInfo skuInfo = new SkuInfo(
                skuId,
                "NK-AF1-01",
                "Giay Nike Air Force",
                "https://image.url",
                new BigDecimal("2900000.00"),
                10,
                true
        );
        when(catalogClient.getSkuDetails(List.of(skuId))).thenReturn(Map.of(skuId, skuInfo));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId(UUID.randomUUID());
            return o;
        });

        when(inventoryClient.reserveStock(any(), any())).thenReturn(
                new ReservationResponse(UUID.randomUUID(), UUID.randomUUID(), "PENDING", Instant.now())
        );

        CreateOrderRequest request = new CreateOrderRequest(
                "So 123 Le Loi, Quan 1, TP.HCM",
                "Nguyen Van A",
                "0901234567",
                null,
                "VNPAY",
                OrderSourceType.REGULAR,
                null,
                List.of(cartItemId)
        );

        OrderResponse response = orderService.createOrder(buyerId, idempotencyKey, request);

        assertNotNull(response);
        assertEquals(OrderStatus.PENDING_PAYMENT, response.status());
        assertEquals(new BigDecimal("5800000.00"), response.subtotal());
        assertEquals(new BigDecimal("5800000.00"), response.total());
        assertEquals("So 123 Le Loi, Quan 1, TP.HCM", response.shippingAddress());
        assertEquals("Nguyen Van A", response.recipientName());
        assertEquals(1, response.items().size());
        assertEquals("Giay Nike Air Force", response.items().get(0).productName());
        assertEquals(1, response.statusHistories().size());

        verify(inventoryClient).reserveStock(any(), any());
        verify(cartItemRepository).deleteAll(any());
    }

    @Test
    @DisplayName("Idempotency: Khi gửi trùng Idempotency-Key thì trả về đơn hàng cũ mà không tạo mới")
    void createOrder_idempotentReplay() {
        Order existingOrder = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("ORD-EXISTING-001")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(new BigDecimal("5800000.00"))
                .shippingFee(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("5800000.00"))
                .shippingAddress("So 123 Le Loi")
                .idempotencyKey(idempotencyKey)
                .build();

        when(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existingOrder));

        CreateOrderRequest request = new CreateOrderRequest(
                "So 123 Le Loi", "Nguyen Van A", "0901234567", null, "VNPAY",
                OrderSourceType.REGULAR, null, null
        );

        OrderResponse response = orderService.createOrder(buyerId, idempotencyKey, request);

        assertNotNull(response);
        assertEquals("ORD-EXISTING-001", response.orderNo());
        verify(orderRepository, never()).save(any());
        verify(inventoryClient, never()).reserveStock(any(), any());
    }

    @Test
    @DisplayName("Báo lỗi CART_EMPTY khi người dùng không có giỏ hàng hoặc giỏ rỗng")
    void createOrder_cartEmpty_throwsException() {
        when(cartRepository.findByUserIdAndStatus(buyerId, CartStatus.ACTIVE)).thenReturn(Optional.empty());

        CreateOrderRequest request = new CreateOrderRequest(
                "Dia chi", "Nguoi Nhan", "0901", null, "COD",
                OrderSourceType.REGULAR, null, null
        );

        assertThrows(BusinessException.class, () -> orderService.createOrder(buyerId, idempotencyKey, request));
    }

    @Test
    @DisplayName("Báo lỗi OUT_OF_STOCK khi tồn kho thực tế nhỏ hơn số lượng yêu cầu")
    void createOrder_outOfStock_throwsException() {
        Cart cart = Cart.builder()
                .id(UUID.randomUUID())
                .userId(buyerId)
                .status(CartStatus.ACTIVE)
                .items(new ArrayList<>())
                .build();

        CartItem cartItem = CartItem.builder()
                .id(cartItemId)
                .cart(cart)
                .skuId(skuId)
                .quantity(5)
                .unitPrice(new BigDecimal("100000.00"))
                .build();
        cart.getItems().add(cartItem);

        when(cartRepository.findByUserIdAndStatus(buyerId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));

        SkuInfo skuInfo = new SkuInfo(skuId, "SKU-01", "San pham A", "url", new BigDecimal("100000.00"), 2, true);
        when(catalogClient.getSkuDetails(List.of(skuId))).thenReturn(Map.of(skuId, skuInfo));

        CreateOrderRequest request = new CreateOrderRequest(
                "Dia chi", "Nguoi Nhan", "0901", null, "COD",
                OrderSourceType.REGULAR, null, null
        );

        assertThrows(BusinessException.class, () -> orderService.createOrder(buyerId, idempotencyKey, request));
    }

    @Test
    @DisplayName("Tra cứu chi tiết đơn hàng: Thành công cho chính chủ người mua")
    void getOrderById_ownerSuccess() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(BigDecimal.TEN)
                .shippingFee(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .shippingAddress("Dia chi")
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderById(orderId, buyerId, false);
        assertNotNull(response);
        assertEquals("ORD-123", response.orderNo());
    }

    @Test
    @DisplayName("Tra cứu chi tiết đơn hàng: Ném AccessDeniedException khi người dùng khác truy cập trái phép")
    void getOrderById_accessDenied() {
        UUID orderId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(AccessDeniedException.class, () -> orderService.getOrderById(orderId, otherUserId, false));
    }

    @Test
    @DisplayName("Tra cứu danh sách đơn hàng của người mua có phân trang")
    void getMyOrders_success() {
        Pageable pageable = PageRequest.of(0, 10);
        Order order = Order.builder()
                .id(UUID.randomUUID())
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(BigDecimal.TEN)
                .shippingFee(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .shippingAddress("Dia chi")
                .build();

        Page<Order> orderPage = new PageImpl<>(List.of(order), pageable, 1);
        when(orderRepository.findByBuyerId(buyerId, pageable)).thenReturn(orderPage);

        PageResponse<OrderResponse> result = orderService.getMyOrders(buyerId, null, pageable);
        assertNotNull(result);
        assertEquals(1, result.content().size());
        assertEquals("ORD-123", result.content().get(0).orderNo());
    }

    @Test
    @DisplayName("Chuyển trạng thái hợp lệ: PENDING_PAYMENT -> PAID và ghi nhận lịch sử audit")
    void changeOrderStatus_validTransition() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(BigDecimal.TEN)
                .shippingFee(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .shippingAddress("Dia chi")
                .statusHistories(new ArrayList<>())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.changeOrderStatus(
                orderId, OrderStatus.PAID, buyerId, "Thanh toan VNPAY thanh cong"
        );

        assertNotNull(response);
        assertEquals(OrderStatus.PAID, response.status());
        assertEquals(1, response.statusHistories().size());
        assertEquals(OrderStatus.PENDING_PAYMENT, response.statusHistories().get(0).fromStatus());
        assertEquals(OrderStatus.PAID, response.statusHistories().get(0).toStatus());
    }

    @Test
    @DisplayName("Chuyển trạng thái sang CANCELLED sẽ kích hoạt giải phóng kho qua InventoryClient")
    void changeOrderStatus_cancelled_releasesReservation() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .sellerId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(BigDecimal.TEN)
                .shippingFee(BigDecimal.ZERO)
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.TEN)
                .shippingAddress("Dia chi")
                .statusHistories(new ArrayList<>())
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.changeOrderStatus(orderId, OrderStatus.CANCELLED, buyerId, "Huy don vi doi y");

        verify(inventoryClient).releaseReservation(orderId);
    }

    @Test
    @DisplayName("Chuyển trạng thái không hợp lệ sẽ ném BusinessException INVALID_STATUS_TRANSITION")
    void changeOrderStatus_invalidTransition_throwsException() {
        UUID orderId = UUID.randomUUID();
        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-123")
                .buyerId(buyerId)
                .status(OrderStatus.PENDING_PAYMENT)
                .build();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () ->
                orderService.changeOrderStatus(orderId, OrderStatus.COMPLETED, buyerId, "Nhay coc trang thai")
        );
    }
}
