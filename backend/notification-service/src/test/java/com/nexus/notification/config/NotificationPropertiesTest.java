package com.nexus.notification.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử cấu hình tham số phân hệ Notification (NotificationProperties).
 */
class NotificationPropertiesTest {

    @Test
    @DisplayName("Kiểm tra giá trị mặc định theo SRS §2.6")
    void testDefaultValues() {
        NotificationProperties props = new NotificationProperties();
        assertTrue(props.isEmailEnabled());
        assertEquals(3, props.getRetryLimit());
        assertTrue(props.isTransactionalMandatory());
        assertEquals("no-reply@nexus.com", props.getSenderFrom());

        props.setEmailEnabled(false);
        props.setRetryLimit(5);
        props.setTransactionalMandatory(false);
        props.setSenderFrom("custom@nexus.com");

        assertEquals(false, props.isEmailEnabled());
        assertEquals(5, props.getRetryLimit());
        assertEquals(false, props.isTransactionalMandatory());
        assertEquals("custom@nexus.com", props.getSenderFrom());
    }
}
