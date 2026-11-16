package com.nexus.fulfillment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Tách HTTP outbox chậm khỏi polling/sweeper; Spring đóng scheduler khi context dừng. */
@Configuration
// Worker HTTP vẫn phải được schedule khi vận hành tắt cả polling carrier và sweeper kho.
@EnableScheduling
public class ShippingEventSchedulingConfig {
    /** Giữ scheduler mặc định riêng để Spring không đưa polling/sweeper vào luồng HTTP event. */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("fulfillment-scheduled-"); }
    /** Mỗi instance một tick event, PostgreSQL chia row giữa instance bằng SKIP LOCKED. */
    @Bean
    public ThreadPoolTaskScheduler shipmentEventScheduler() { return scheduler("shipment-event-"); }
    /** Dispatch có HTTP riêng, không chiếm tick event/polling; Spring dừng scheduler khi context đóng. */
    @Bean
    public ThreadPoolTaskScheduler shipmentDispatchScheduler() { return scheduler("shipment-dispatch-"); }
    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix); scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
}
