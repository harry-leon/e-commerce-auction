package com.nexus.commerce.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.nexus.commerce.entity.Order;
import com.nexus.commerce.entity.Payment;
import com.nexus.commerce.enums.OrderStatus;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cổng mặc định tắt và dữ liệu chưa xác nhận không được ghi queue; nghiệp vụ transaction thật có test PostgreSQL riêng. */
class ShippingHandoffOutboxTest {
    /** Tắt producer không chạm DB hoặc gọi mạng, kể cả caller chưa có snapshot đầy đủ. */
    @Test
    void disabledDoesNotTouchDatabase() {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        assertDoesNotThrow(() -> new ShippingHandoffOutbox(jdbc, false).enqueue(null, null));
        verifyNoInteractions(jdbc);
    }

    /** PAID tự đặt không đủ: thiếu Payment/kho hoặc COD đều bị skip trước truy vấn DB. */
    @Test
    void rejectsIncompleteOrCodDataBeforeDatabase() {
        var jdbc = org.mockito.Mockito.mock(JdbcTemplate.class);
        var outbox = new ShippingHandoffOutbox(jdbc, true);
        var order = Order.builder().buyerId(UUID.randomUUID()).status(OrderStatus.PAID).paymentMethod("COD").total(BigDecimal.TEN).build();
        var payment = new Payment(); payment.setOrderId(order.getId());
        outbox.enqueue(null, payment); outbox.enqueue(order, null); outbox.enqueue(order, payment);
        order.setPaymentMethod("VNPAY"); payment.setProvider("VNPAY"); payment.setStatus("SUCCESS");
        payment.setInventoryStatus("PENDING"); outbox.enqueue(order, payment);
        verifyNoInteractions(jdbc);
    }
}
