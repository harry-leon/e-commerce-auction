package com.nexus.commerce.service.impl;

import com.nexus.commerce.client.CatalogClient;
import com.nexus.commerce.client.InventoryClient;
import com.nexus.commerce.client.dto.ReservationItem;
import com.nexus.commerce.client.dto.SkuInfo;
import com.nexus.commerce.dto.mapper.OrderMapper;
import com.nexus.commerce.dto.request.CreateOrderRequest;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.PageResponse;
import com.nexus.commerce.entity.Cart;
import com.nexus.commerce.entity.CartItem;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.entity.OrderStatusHistory;
import com.nexus.commerce.enums.CartStatus;
import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import com.nexus.commerce.exception.BusinessException;
import com.nexus.commerce.exception.ResourceNotFoundException;
import com.nexus.commerce.repository.CartItemRepository;
import com.nexus.commerce.repository.CartRepository;
import com.nexus.commerce.repository.OrderItemRepository;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.commerce.repository.OrderStatusHistoryRepository;
import com.nexus.commerce.service.OrderService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Triển khai nghiệp vụ OrderService cho tầng Domain và Persistence của Đơn hàng (HG-S1-3).
 *
 * <p>1. Chức năng:
 * - Tiếp nhận đơn hàng từ Checkout, thực hiện idempotency check để chống tạo lặp đơn khi retry mạng.
 * - Snapshot giá bán, tên sản phẩm, mã SKU từ Catalog để dữ liệu đơn hàng bất biến.
 * - Gọi InventoryClient để giữ hàng tồn kho (15 phút).
 * - Lưu Order và OrderItem vào cơ sở dữ liệu với trạng thái khởi tạo PENDING_PAYMENT.
 * - Ghi nhận bản ghi lịch sử trạng thái ban đầu vào order_status_history (Audit Trail).
 * - Dọn dẹp các SKU đã mua khỏi giỏ hàng đang hoạt động của người mua.
 * - Kiểm soát chuyển đổi trạng thái đơn hàng tuân thủ Order State Machine (API_SPEC_CART_ORDER.md §6).
 *
 * <p>2. Đầu vào và kết quả:
 * - Đầu vào: Thông tin đặt hàng (CreateOrderRequest), buyerId, Idempotency-Key từ HTTP Header.
 * - Kết quả: OrderResponse hoàn chỉnh chứa snapshot mặt hàng, tổng tiền và audit trail lịch sử.
 *
 * <p>3. Luồng xử lý:
 * Idempotency Check -> Validate Giỏ hàng -> Re-validate Catalog & Tồn kho -> Gọi Giữ kho
 * -> Persist Order & Items -> Ghi Audit History -> Xóa Giỏ hàng -> Return OrderResponse.
 *
 * <p>4. Quy tắc và lý do:
 * - Idempotent Order Creation: Nếu cùng Idempotency-Key đã xử lý, trả lại đơn cũ mà không tạo thêm.
 * - State Machine: Chặn chuyển đổi trạng thái trái phép bằng OrderStatus.canTransitionTo().
 * - Khi hủy đơn (CANCELLED): Gọi releaseReservation để hoàn trả lại hàng tồn kho.
 *
 * <p>5. Lỗi và giới hạn:
 * - Quăng BusinessException khi giỏ hàng trống, SKU không tồn tại/không đủ tồn kho, hoặc trạng thái chuyển đổi không hợp lệ.
 * - Quăng ResourceNotFoundException khi không tìm thấy đơn hàng.
 * - Quăng AccessDeniedException khi truy cập đơn hàng của người dùng khác.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final UUID DEFAULT_SELLER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final OrderMapper orderMapper;

    @Override
    @Transactional
    public OrderResponse createOrder(UUID buyerId, String idempotencyKey, CreateOrderRequest request) {
        log.info("[OrderService] Tiếp nhận tạo đơn hàng cho buyerId={}, idempotencyKey={}", buyerId, idempotencyKey);

        // 1. Kiểm tra Idempotency-Key nếu có: Nếu đã tồn tại đơn hàng với key này, trả lại đơn hàng cũ
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Order> existingOrderOpt = orderRepository.findByIdempotencyKey(idempotencyKey.trim());
            if (existingOrderOpt.isPresent()) {
                log.info("[OrderService] Đơn hàng với Idempotency-Key {} đã tồn tại (orderNo={}), trả về đơn cũ",
                        idempotencyKey, existingOrderOpt.get().getOrderNo());
                return orderMapper.toOrderResponse(existingOrderOpt.get());
            }
        }

        // 2. Lấy giỏ hàng đang active của buyer
        Cart activeCart = cartRepository.findByUserIdAndStatus(buyerId, CartStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException("CART_EMPTY", "Người dùng không có giỏ hàng hoạt động"));

        List<CartItem> cartItems = activeCart.getItems();
        if (cartItems == null || cartItems.isEmpty()) {
            throw new BusinessException("CART_EMPTY", "Giỏ hàng của người dùng đang trống");
        }

        // 3. Lọc danh sách mặt hàng cần mua theo selectedItemIds (nếu chỉ định mua một phần giỏ hàng)
        List<CartItem> selectedCartItems;
        if (request.selectedItemIds() != null && !request.selectedItemIds().isEmpty()) {
            selectedCartItems = cartItems.stream()
                    .filter(item -> request.selectedItemIds().contains(item.getId()))
                    .toList();
            if (selectedCartItems.isEmpty()) {
                throw new BusinessException("NO_ITEMS_SELECTED", "Không tìm thấy mặt hàng được chọn trong giỏ hàng");
            }
        } else {
            selectedCartItems = new ArrayList<>(cartItems);
        }

        // 4. Lấy thông tin giá và tồn kho thời gian thực từ Catalog Service để re-validate
        List<UUID> skuIds = selectedCartItems.stream().map(CartItem::getSkuId).distinct().toList();
        Map<UUID, SkuInfo> skuDetails = catalogClient.getSkuDetails(skuIds);

        BigDecimal subtotal = BigDecimal.ZERO;
        List<ReservationItem> reservationItems = new ArrayList<>();

        for (CartItem cartItem : selectedCartItems) {
            SkuInfo sku = skuDetails.get(cartItem.getSkuId());
            if (sku == null || !Boolean.TRUE.equals(sku.isActive())) {
                throw new BusinessException("SKU_UNAVAILABLE",
                        "Sản phẩm với SKU ID " + cartItem.getSkuId() + " hiện không khả dụng");
            }

            int requestedQty = cartItem.getQuantity();
            int stockAvailable = sku.stockQuantity() != null ? sku.stockQuantity() : 0;
            if (stockAvailable < requestedQty) {
                throw new BusinessException("OUT_OF_STOCK",
                        "Sản phẩm " + sku.productName() + " không đủ số lượng tồn kho (còn: " + stockAvailable + ")");
            }

            BigDecimal unitPrice = sku.price() != null ? sku.price() : cartItem.getUnitPrice();
            if (unitPrice == null) {
                unitPrice = BigDecimal.ZERO;
            }

            BigDecimal itemTotal = unitPrice.multiply(BigDecimal.valueOf(requestedQty)).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(itemTotal);

            reservationItems.add(new ReservationItem(sku.skuId(), requestedQty));
        }

        // 5. Tính toán các khoản phí & tổng tiền theo công thức SRS §3.4.3
        BigDecimal shippingFee = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal total = subtotal.subtract(discount).max(BigDecimal.ZERO).add(shippingFee);

        // 6. Sinh mã đơn hàng chuẩn định dạng ORD-YYYYMMDD-XXXXXX
        String orderNo = generateOrderNo();

        // 7. Tạo thực thể Order
        Order order = Order.builder()
                .orderNo(orderNo)
                .buyerId(buyerId)
                .sellerId(DEFAULT_SELLER_ID)
                .sourceType(request.sourceType() != null ? request.sourceType() : OrderSourceType.REGULAR)
                .auctionId(request.auctionId())
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(subtotal)
                .shippingFee(shippingFee)
                .discount(discount)
                .total(total)
                .shippingAddress(request.shippingAddress())
                .recipientName(request.recipientName())
                .recipientPhone(request.recipientPhone())
                .idempotencyKey(idempotencyKey != null ? idempotencyKey.trim() : null)
                .items(new ArrayList<>())
                .statusHistories(new ArrayList<>())
                .build();

        // 8. Tạo và đính kèm các OrderItem snapshot
        for (CartItem cartItem : selectedCartItems) {
            SkuInfo sku = skuDetails.get(cartItem.getSkuId());
            BigDecimal unitPrice = sku.price() != null ? sku.price() : cartItem.getUnitPrice();
            if (unitPrice == null) {
                unitPrice = BigDecimal.ZERO;
            }

            OrderItem orderItem = OrderItem.builder()
                    .skuId(sku.skuId())
                    .skuCode(sku.skuCode())
                    .productName(sku.productName())
                    .unitPrice(unitPrice)
                    .quantity(cartItem.getQuantity())
                    .build();
            order.addItem(orderItem);
        }

        // 9. Ghi nhận vết kiểm toán trạng thái ban đầu: null -> PENDING_PAYMENT
        OrderStatusHistory initialHistory = OrderStatusHistory.builder()
                .fromStatus(null)
                .toStatus(OrderStatus.PENDING_PAYMENT)
                .changedBy(buyerId)
                .reason("Khởi tạo đơn hàng từ Checkout")
                .build();
        order.addStatusHistory(initialHistory);

        // 10. Lưu Order vào Database
        Order savedOrder = orderRepository.save(order);

        // 11. Gọi InventoryClient giữ kho (Reservation với timeout 15 phút)
        inventoryClient.reserveStock(savedOrder.getId(), reservationItems);

        // 12. Dọn dẹp các mặt hàng đã đặt khỏi giỏ hàng
        cartItemRepository.deleteAll(selectedCartItems);
        activeCart.getItems().removeAll(selectedCartItems);

        log.info("[OrderService] Đã tạo đơn hàng thành công: id={}, orderNo={}, total={}",
                savedOrder.getId(), savedOrder.getOrderNo(), savedOrder.getTotal());

        return orderMapper.toOrderResponse(savedOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(UUID orderId, UUID requesterId, boolean isAdminOrSeller) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId));

        if (!isAdminOrSeller && !order.getBuyerId().equals(requesterId)) {
            throw new AccessDeniedException("Bạn không có quyền truy cập thông tin đơn hàng này");
        }

        return orderMapper.toOrderResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> getMyOrders(UUID buyerId, OrderStatus status, Pageable pageable) {
        Page<Order> orderPage = (status != null)
                ? orderRepository.findByBuyerIdAndStatus(buyerId, status, pageable)
                : orderRepository.findByBuyerId(buyerId, pageable);

        List<OrderResponse> content = orderPage.getContent().stream()
                .map(orderMapper::toOrderResponse)
                .toList();

        return new PageResponse<>(
                content,
                orderPage.getNumber(),
                orderPage.getSize(),
                orderPage.getTotalElements(),
                orderPage.getTotalPages(),
                orderPage.isFirst(),
                orderPage.isLast()
        );
    }

    @Override
    @Transactional
    public OrderResponse changeOrderStatus(UUID orderId, OrderStatus newStatus, UUID changedBy, String reason) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Đơn hàng không tồn tại: " + orderId));

        OrderStatus currentStatus = order.getStatus();

        // Kiểm tra tính hợp lệ của chuyển đổi trạng thái theo State Machine
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new BusinessException("INVALID_STATUS_TRANSITION",
                    String.format("Không thể chuyển trạng thái đơn hàng từ %s sang %s", currentStatus, newStatus));
        }

        // Nếu chuyển sang CANCELLED, gọi InventoryClient để giải phóng tồn kho đã giữ
        if (newStatus == OrderStatus.CANCELLED) {
            inventoryClient.releaseReservation(orderId);
        }

        // Cập nhật trạng thái mới cho đơn hàng
        order.setStatus(newStatus);

        // Ghi vết kiểm toán (Audit Trail)
        OrderStatusHistory history = OrderStatusHistory.builder()
                .fromStatus(currentStatus)
                .toStatus(newStatus)
                .changedBy(changedBy)
                .reason(reason)
                .build();
        order.addStatusHistory(history);

        Order updatedOrder = orderRepository.save(order);

        log.info("[OrderService] Cập nhật trạng thái đơn hàng {}: {} -> {}, thực hiện bởi={}",
                order.getOrderNo(), currentStatus, newStatus, changedBy);

        return orderMapper.toOrderResponse(updatedOrder);
    }

    /**
     * Sinh mã hiển thị đơn hàng duy nhất theo định dạng ORD-YYYYMMDD-XXXXXX.
     */
    private String generateOrderNo() {
        String datePart = LocalDate.now().format(DATE_FORMATTER);
        String randomSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ORD-" + datePart + "-" + randomSuffix;
    }
}
