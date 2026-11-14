package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.repository.OrderRepository;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Snapshot không được biến giá tạm thành giá chốt hoặc tự bù thông tin người nhận thiếu ở đơn cũ. */
class ShippingOrderSnapshotServiceTest {
    /** Đơn COD giá chưa chốt trả total null, đơn đã chốt giữ đúng snapshot, không gọi nguồn giá mới. */
    @Test
    void preservesMissingRecipientAndDoesNotExposeUnfinalizedTotal() {
        var orders = mock(OrderRepository.class);
        var order = new Order();
        order.setTotal(new BigDecimal("50000"));
        order.setPriceFinalized(false);
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        var service = new ShippingOrderSnapshotService(orders);
        assertNull(service.get(order.getId()).total());
        assertNull(service.get(order.getId()).recipientName());
        order.setPriceFinalized(true);
        assertEquals(new BigDecimal("50000"), service.get(order.getId()).total());
        UUID missing = UUID.randomUUID();
        when(orders.findById(missing)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.get(missing));
    }
}
