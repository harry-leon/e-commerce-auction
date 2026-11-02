package com.nexus.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.nexus.notification.config.NotificationProperties;
import com.nexus.notification.dto.OrderEmailRequest;
import com.nexus.notification.enums.NotificationStatus;
import com.nexus.notification.exception.EmailDeliveryException;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Flyway/JPA/transaction thật; chỉ SMTP được mock để kiểm tra retry, khóa event và trạng thái không gửi giả. */
@SpringBootTest(properties = {
        "spring.datasource.url=${NEXUS_TEST_DB_URL}", "spring.datasource.username=postgres", "spring.datasource.password=",
        "spring.cloud.config.enabled=false", "eureka.client.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_DB_URL", matches = ".+")
class TransactionalEmailPostgresTest {
    @Autowired private TransactionalEmailService service;
    @Autowired private NotificationProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailSender sender;

    @BeforeEach
    void setup() {
        properties.setEmailEnabled(true);
        properties.setRetryLimit(3);
    }

    /** Người nhận/số tiền là dữ liệu thật của event fixture, không dùng địa chỉ hoặc tổng tiền mặc định. */
    private OrderEmailRequest request() {
        return OrderEmailRequest.ofOrderPaid(UUID.randomUUID(), "ORD-EMAIL-TEST", UUID.randomUUID(),
                "buyer@example.test", "Người mua", new BigDecimal("50000.00"), "VNPAY", null,
                "EMAIL-" + UUID.randomUUID());
    }

    /** Callback đồng thời cùng event chỉ gửi SMTP một lần và lưu đúng một notification/attempt. */
    @Test
    void concurrentDeliveryUsesOnePersistedNotification() throws Exception {
        var request = request();
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { gate.await(); return service.processOrderTransactionalEmail(request); });
            var second = executor.submit(() -> { gate.await(); return service.processOrderTransactionalEmail(request); });
            gate.countDown();
            var firstResponse = first.get(20, TimeUnit.SECONDS);
            var secondResponse = second.get(20, TimeUnit.SECONDS);
            assertEquals(NotificationStatus.SENT, firstResponse.status());
            assertEquals(firstResponse.notificationId(), secondResponse.notificationId());
        }
        verify(sender, times(1)).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_id=?", Integer.class, request.eventId()));
    }

    /** SMTP lỗi rồi hồi phục phải dùng lại notification cũ và nối attempts thay vì vi phạm unique event. */
    @Test
    void failedDeliveryCanRetryTheSameEvent() {
        var request = request();
        doThrow(new EmailDeliveryException("SMTP unavailable", null)).when(sender)
                .sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
        var failed = service.processOrderTransactionalEmail(request);
        assertEquals(NotificationStatus.FAILED, failed.status());
        doNothing().when(sender).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
        var sent = service.processOrderTransactionalEmail(request);
        assertEquals(NotificationStatus.SENT, sent.status());
        assertEquals(failed.notificationId(), sent.notificationId());
        assertEquals(4, jdbc.queryForObject("SELECT max(attempt_no) FROM notification_attempts WHERE notification_id=?",
                Integer.class, sent.notificationId()));
    }

    /** Cấu hình tắt không đánh dấu SENT; bật lại phải gửi được event còn PENDING. */
    @Test
    void disabledEmailRemainsPendingUntilSmtpIsEnabled() {
        var request = request();
        properties.setEmailEnabled(false);
        try {
            assertEquals(NotificationStatus.PENDING, service.processOrderTransactionalEmail(request).status());
        } finally {
            properties.setEmailEnabled(true);
        }
        assertEquals(NotificationStatus.SENT, service.processOrderTransactionalEmail(request).status());
        verify(sender, times(1)).sendEmail(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /** Entry point @Async vẫn phải mở transaction thật để advisory lock và lưu notification hoạt động cùng nhau. */
    @Test
    void asyncDeliveryCommitsItsNotification() throws Exception {
        var request = request();
        assertEquals(NotificationStatus.SENT, service.sendOrderTransactionalEmail(request).get(20, TimeUnit.SECONDS).status());
        assertEquals("SENT", jdbc.queryForObject("SELECT status FROM notifications WHERE event_id=?", String.class, request.eventId()));
    }
}
