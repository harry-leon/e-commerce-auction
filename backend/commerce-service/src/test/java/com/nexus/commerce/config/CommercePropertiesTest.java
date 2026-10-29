package com.nexus.commerce.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

/**
 * Kiểm thử tính năng cấu hình tập trung CommerceProperties.
 * Đảm bảo các giá trị mặc định chuẩn xác và hỗ trợ thay đổi runtime qua @RefreshScope.
 */
class CommercePropertiesTest {

    /** Phí ship từ config không được âm; validate nested Order trước khi có thể làm sai giá preview. */
    @Test
    void rejectsNegativeShippingFee() {
        CommerceProperties properties = new CommerceProperties();
        properties.getOrder().setShippingFee(new BigDecimal("-1.00"));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(properties).stream()
                    .anyMatch(v -> v.getPropertyPath().toString().equals("order.shippingFee")));
        }
    }

    @Test
    void defaultValues_shouldBeValid() {
        CommerceProperties properties = new CommerceProperties();

        assertNotNull(properties.getCart());
        assertEquals(7, properties.getCart().getExpirationDays());
        assertEquals(10080, properties.getCart().getExpireAfterMinutes());
        assertEquals(50, properties.getCart().getMaxItemsPerCart());

        assertNotNull(properties.getOrder());
        assertEquals(10, properties.getOrder().getMaxDailyOrders());
        assertEquals(30, properties.getOrder().getCancellationTimeoutMinutes());
    }

    @Test
    void customValues_shouldBeUpdatedProperly() {
        CommerceProperties properties = new CommerceProperties();

        properties.getCart().setExpirationDays(14);
        properties.getCart().setExpireAfterMinutes(20160);
        properties.getCart().setMaxItemsPerCart(100);

        properties.getOrder().setMaxDailyOrders(20);
        properties.getOrder().setCancellationTimeoutMinutes(60);

        assertEquals(14, properties.getCart().getExpirationDays());
        assertEquals(20160, properties.getCart().getExpireAfterMinutes());
        assertEquals(100, properties.getCart().getMaxItemsPerCart());
        assertEquals(20, properties.getOrder().getMaxDailyOrders());
        assertEquals(60, properties.getOrder().getCancellationTimeoutMinutes());
    }
}
