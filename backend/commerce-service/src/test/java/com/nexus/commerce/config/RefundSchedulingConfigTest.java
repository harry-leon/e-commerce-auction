package com.nexus.commerce.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Refund HTTP chậm không được giữ luồng của quét hạn đơn/outbox mặc định; không gọi HTTP hoặc DB trong test này. */
class RefundSchedulingConfigTest {
    /** Giữ một task refund đang chạy, chứng minh task mặc định vẫn hoàn tất trước khi refund được giải phóng. */
    @Test
    void blockingRefundDoesNotBlockDefaultScheduler() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch regularCompleted = new CountDownLatch(1);
        try (var context = new AnnotationConfigApplicationContext(RefundSchedulingConfig.class)) {
            var refunds = context.getBean("refundTaskScheduler", ThreadPoolTaskScheduler.class);
            var regular = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            refunds.schedule(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }, Instant.now());
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                regular.schedule(regularCompleted::countDown, Instant.now());
                assertTrue(regularCompleted.await(3, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }
}
