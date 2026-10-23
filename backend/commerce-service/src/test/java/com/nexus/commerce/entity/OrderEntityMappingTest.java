package com.nexus.commerce.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử đơn vị cho mapping thực thể Order, OrderItem và OrderStatusHistory
 * cùng các quy tắc chuyển đổi trạng thái của OrderStatus enum.
 */
class OrderEntityMappingTest {

    @Test
    @DisplayName("Kiểm tra ánh xạ quan hệ và các helper method của Order")
    void testOrderEntityMappingAndRelationships() {
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();

        Order order = Order.builder()
                .id(orderId)
                .orderNo("ORD-20261023-AABBCC")
                .buyerId(buyerId)
                .sellerId(sellerId)
                .sourceType(OrderSourceType.REGULAR)
                .status(OrderStatus.PENDING_PAYMENT)
                .subtotal(new BigDecimal("100000.00"))
                .shippingFee(new BigDecimal("20000.00"))
                .discount(BigDecimal.ZERO)
                .total(new BigDecimal("120000.00"))
                .shippingAddress("123 Duong Le Loi, Quan 1, TP.HCM")
                .recipientName("Nguyen Van A")
                .recipientPhone("0901234567")
                .idempotencyKey("key-123456")
                .build();

        OrderItem item = OrderItem.builder()
                .skuId(skuId)
                .skuCode("SKU-TEST-01")
                .productName("San Pham Test")
                .unitPrice(new BigDecimal("100000.00"))
                .quantity(1)
                .build();

        OrderStatusHistory history = OrderStatusHistory.builder()
                .fromStatus(null)
                .toStatus(OrderStatus.PENDING_PAYMENT)
                .changedBy(buyerId)
                .reason("Khoi tao don hang")
                .build();

        order.addItem(item);
        order.addStatusHistory(history);

        assertEquals(1, order.getItems().size());
        assertEquals(order, item.getOrder());
        assertEquals(skuId, item.getSkuId());
        assertEquals("SKU-TEST-01", item.getSkuCode());

        assertEquals(1, order.getStatusHistories().size());
        assertEquals(order, history.getOrder());
        assertEquals(OrderStatus.PENDING_PAYMENT, history.getToStatus());
        assertEquals(buyerId, history.getChangedBy());
    }

    @Test
    @DisplayName("Kiểm tra ma trận chuyển đổi trạng thái hợp lệ và không hợp lệ của OrderStatus")
    void testOrderStatusStateTransitions() {
        // Hợp lệ từ PENDING_PAYMENT
        assertTrue(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAID));
        assertTrue(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.SHIPPED));
        assertFalse(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.COMPLETED));

        // Hợp lệ từ PAID
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.PROCESSING));
        assertTrue(OrderStatus.PAID.canTransitionTo(OrderStatus.REFUNDED));
        assertFalse(OrderStatus.PAID.canTransitionTo(OrderStatus.PENDING_PAYMENT));

        // Hợp lệ từ PROCESSING
        assertTrue(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.SHIPPED));
        assertTrue(OrderStatus.PROCESSING.canTransitionTo(OrderStatus.CANCELLED));

        // Hợp lệ từ SHIPPED
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED));
        assertTrue(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.CANCELLED));

        // Hợp lệ từ DELIVERED
        assertTrue(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.COMPLETED));
        assertTrue(OrderStatus.DELIVERED.canTransitionTo(OrderStatus.REFUNDED));

        // Các trạng thái kết thúc không thể chuyển tiếp
        assertFalse(OrderStatus.COMPLETED.canTransitionTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.CANCELLED.canTransitionTo(OrderStatus.PAID));
        assertFalse(OrderStatus.REFUNDED.canTransitionTo(OrderStatus.PROCESSING));

        // null an toàn
        assertFalse(OrderStatus.PENDING_PAYMENT.canTransitionTo(null));
    }
}
