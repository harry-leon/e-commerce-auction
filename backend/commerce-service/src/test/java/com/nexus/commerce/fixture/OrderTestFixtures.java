package com.nexus.commerce.fixture;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * Lớp tiện ích cung cấp dữ liệu mẫu (Test Fixtures) và JWT Security Mock cho Order Service.
 * Chức năng: Khởi tạo dữ liệu phục vụ các kịch bản kiểm thử E2E và lát cắt MVC.
 * Đầu vào và kết quả: Cung cấp đối tượng thực thể Order, OrderItem, DTO và Mock JWT PostProcessor.
 * Luồng xử lý: Tạo thực thể với các giá trị hợp lệ, hỗ trợ mock chuỗi gọi tầng Service.
 * Quy tắc và lý do: Tuân thủ quy định Checkstyle, không dùng wildcard import và comment tiếng Việt theo AGENTS.md.
 */
public final class OrderTestFixtures {

    private OrderTestFixtures() {
        // Chặn khởi tạo trực tiếp lớp tiện ích tĩnh
    }

    /**
     * Khởi tạo đối tượng Order mẫu với trạng thái và thông tin người mua, người bán xác định.
     */
    public static Order createMockOrder(UUID orderId, UUID buyerId, UUID sellerId, OrderStatus status) {
        UUID effectiveOrderId = (orderId != null) ? orderId : UUID.randomUUID();
        UUID effectiveBuyerId = (buyerId != null) ? buyerId : UUID.randomUUID();
        UUID effectiveSellerId = (sellerId != null) ? sellerId : UUID.randomUUID();
        OrderStatus effectiveStatus = (status != null) ? status : OrderStatus.PENDING_PAYMENT;

        Order order = Order.builder()
                .id(effectiveOrderId)
                .orderNo("ORD-" + System.currentTimeMillis())
                .buyerId(effectiveBuyerId)
                .sellerId(effectiveSellerId)
                .sourceType(OrderSourceType.REGULAR)
                .status(effectiveStatus)
                .subtotal(new BigDecimal("200000.00"))
                .shippingFee(new BigDecimal("30000.00"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("230000.00"))
                .shippingAddress("Số 123 Lê Lợi, Phường Bến Nghé, Quận 1, TP. Hồ Chí Minh")
                .idempotencyKey(UUID.randomUUID().toString())
                .createdAt(Instant.now())
                .items(new ArrayList<>())
                .statusHistory(new ArrayList<>())
                .build();

        OrderItem item = createMockOrderItem(
                order,
                UUID.randomUUID(),
                "Giày Thể Thao Sneaker Nexus Air Pro",
                "SKU-NEXUS-01",
                new BigDecimal("100000.00"),
                2
        );
        order.getItems().add(item);

        OrderStatusHistory history = createMockHistory(
                order,
                null,
                effectiveStatus,
                "Đơn hàng được khởi tạo thành công"
        );
        order.getStatusHistory().add(history);

        return order;
    }

    /**
     * Khởi tạo đối tượng OrderItem mẫu gắn kèm với Order cha.
     */
    public static OrderItem createMockOrderItem(
            Order order,
            UUID skuId,
            String productName,
            String skuCode,
            BigDecimal unitPrice,
            int quantity
    ) {
        return OrderItem.builder()
                .id(UUID.randomUUID())
                .order(order)
                .skuId((skuId != null) ? skuId : UUID.randomUUID())
                .productName(productName)
                .skuCode(skuCode)
                .unitPrice(unitPrice)
                .quantity(quantity)
                .build();
    }

    /**
     * Khởi tạo đối tượng OrderStatusHistory mẫu ghi nhận nhật ký chuyển trạng thái.
     */
    public static OrderStatusHistory createMockHistory(Order order, OrderStatus from, OrderStatus to, String reason) {
        return OrderStatusHistory.builder()
                .id(UUID.randomUUID())
                .order(order)
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .changedAt(Instant.now())
                .build();
    }

    /**
     * Khởi tạo DTO phản hồi chi tiết đơn hàng (OrderDetailResponse) từ đối tượng Order thực thể.
     */
    public static OrderDetailResponse createMockOrderDetailResponse(Order order) {
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                BigDecimal subtotal = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                itemResponses.add(new OrderItemResponse(
                        item.getId(),
                        item.getSkuId(),
                        item.getProductName(),
                        item.getSkuCode(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        subtotal, item.getEstimatedUnitPrice(), null
                ));
            }
        }

        List<OrderStatusHistoryResponse> historyResponses = new ArrayList<>();
        if (order.getStatusHistory() != null) {
            for (OrderStatusHistory hist : order.getStatusHistory()) {
                historyResponses.add(new OrderStatusHistoryResponse(
                        hist.getId(),
                        hist.getFromStatus(),
                        hist.getToStatus(),
                        hist.getReason(),
                        hist.getChangedAt(),
                        "SYSTEM"
                ));
            }
        }

        return OrderDetailResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .buyerId(order.getBuyerId())
                .sellerId(order.getSellerId())
                .sourceType(order.getSourceType())
                .auctionId(order.getAuctionId())
                .voucherId(order.getVoucherId())
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

    /**
     * Khởi tạo DTO phản hồi danh sách tóm tắt (OrderSummaryResponse).
     */
    public static OrderSummaryResponse createMockOrderSummaryResponse(Order order) {
        int count = (order.getItems() != null) ? order.getItems().size() : 0;
        return OrderSummaryResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .buyerId(order.getBuyerId())
                .sellerId(order.getSellerId())
                .sourceType(order.getSourceType())
                .status(order.getStatus())
                .total(order.getTotal())
                .itemCount(count)
                .shippingAddress(order.getShippingAddress())
                .createdAt(order.getCreatedAt())
                .build();
    }

    /**
     * Tạo request cập nhật trạng thái đơn hàng (UpdateOrderStatusRequest).
     */
    public static UpdateOrderStatusRequest createMockUpdateOrderStatusRequest(OrderStatus targetStatus, String reason) {
        return new UpdateOrderStatusRequest(targetStatus, reason);
    }

    /**
     * Tạo request hủy đơn hàng (CancelOrderRequest).
     */
    public static CancelOrderRequest createMockCancelOrderRequest(String reason) {
        return new CancelOrderRequest(reason);
    }

    /**
     * Giả lập JWT token cho vai trò Người mua (ROLE_BUYER).
     */
    public static JwtRequestPostProcessor mockBuyerJwt(UUID buyerId) {
        return jwt().jwt(j -> j.subject(buyerId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_BUYER"));
    }

    /**
     * Giả lập JWT token cho vai trò Người bán (ROLE_SELLER).
     */
    public static JwtRequestPostProcessor mockSellerJwt(UUID sellerId) {
        return jwt().jwt(j -> j.subject(sellerId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_SELLER"));
    }

    /**
     * Giả lập JWT token cho vai trò Quản trị viên (ROLE_ADMIN).
     */
    public static JwtRequestPostProcessor mockAdminJwt(UUID adminId) {
        return jwt().jwt(j -> j.subject(adminId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
