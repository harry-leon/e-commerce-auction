package com.nexus.commerce.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Scheduler riêng cho HTTP refund chậm, tránh giữ luồng quét hạn đơn/outbox khác; Spring dừng khi context đóng. */
@Configuration
public class RefundSchedulingConfig {
    /** Tách retry thông báo khỏi dispatch tài chính và quét hạn đơn; HTTP chậm chỉ giữ row outbox tương ứng. */
    @Bean
    public ThreadPoolTaskScheduler refundNotificationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("refund-notification-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    /** Giữ scheduler mặc định riêng cho job không chỉ định tên; tránh Spring chọn nhầm bean refund duy nhất. */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("commerce-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
    /** Một tick/instance, còn PostgreSQL SKIP LOCKED phân chia giữa instance; không chạy song song tick cùng worker. */
    @Bean
    public ThreadPoolTaskScheduler refundTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("refund-dispatch-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
