package com.nexus.commerce.dto.mapper;

import com.nexus.commerce.dto.response.OrderItemResponse;
import com.nexus.commerce.dto.response.OrderResponse;
import com.nexus.commerce.dto.response.OrderStatusHistoryResponse;
import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.OrderItem;
import com.nexus.commerce.entity.OrderStatusHistory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Component chịu trách nhiệm chuyển đổi (mapping) giữa các Entity đơn hàng và DTO phản hồi.
 *
 * <p>Chức năng: Chuyển đổi an toàn từ Order, OrderItem, OrderStatusHistory sang OrderResponse,
 * OrderItemResponse, OrderStatusHistoryResponse.
 */
@Component
public class OrderMapper {

    /**
     * Chuyển đổi entity OrderItem sang OrderItemResponse.
     *
     * @param item Entity dòng sản phẩm trong đơn hàng
     * @return OrderItemResponse chứa snapshot chi tiết
     */
    public OrderItemResponse toItemResponse(OrderItem item) {
        if (item == null) {
            return null;
        }

        BigDecimal unitPrice = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
        BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);

        return new OrderItemResponse(
                item.getId(),
                item.getSkuId(),
                item.getSkuCode(),
                item.getProductName(),
                unitPrice,
                quantity,
                subtotal
        );
    }

    /**
     * Chuyển đổi entity OrderStatusHistory sang OrderStatusHistoryResponse.
     *
     * @param history Entity lịch sử biến đổi trạng thái
     * @return OrderStatusHistoryResponse
     */
    public OrderStatusHistoryResponse toStatusHistoryResponse(OrderStatusHistory history) {
        if (history == null) {
            return null;
        }

        return new OrderStatusHistoryResponse(
                history.getId(),
                history.getOrder() != null ? history.getOrder().getId() : null,
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getChangedBy(),
                history.getChangedAt()
        );
    }

    /**
     * Chuyển đổi entity Order sang OrderResponse hoàn chỉnh.
     *
     * @param order Entity đơn hàng
     * @return OrderResponse
     */
    public OrderResponse toOrderResponse(Order order) {
        if (order == null) {
            return null;
        }

        List<OrderItemResponse> itemResponses = order.getItems() != null
                ? order.getItems().stream().map(this::toItemResponse).toList()
                : Collections.emptyList();

        List<OrderStatusHistoryResponse> historyResponses = order.getStatusHistories() != null
                ? order.getStatusHistories().stream().map(this::toStatusHistoryResponse).toList()
                : Collections.emptyList();

        return new OrderResponse(
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
                order.getRecipientName(),
                order.getRecipientPhone(),
                order.getIdempotencyKey(),
                order.getCreatedAt(),
                itemResponses,
                historyResponses
        );
    }
}
