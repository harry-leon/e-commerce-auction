package com.nexus.fulfillment.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/** Bảo vệ worker HTTP không phụ thuộc @EnableScheduling ở polling/sweeper bị tắt bằng feature flag. */
class ShippingEventSchedulingConfigTest {
    /** Chỉ nạp config HTTP và probe, không có scheduler carrier/kho hoặc DB/downstream. */
    @Test
    void enablesSchedulingWithoutPollingOrReservationConfiguration() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(ShippingEventSchedulingConfig.class, ProbeConfig.class)) {
            assertTrue(context.getBean(Probe.class).called.await(3, TimeUnit.SECONDS));
        }
    }
    @Configuration
    static class ProbeConfig {
        @Bean Probe probe() { return new Probe(); }
    }
    /** Fixture Scheduled đại diện tick HTTP, chỉ ghi latch thay vì gọi service hoặc mạng. */
    static class Probe {
        final CountDownLatch called = new CountDownLatch(1);
        @Scheduled(fixedDelay = 10, scheduler = "shipmentEventScheduler")
        public void tick() { called.countDown(); }
    }
}
