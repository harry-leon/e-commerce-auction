package com.nexus.auction.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Cấu hình các Bean hệ thống dùng chung cho auction-service.
 *
 * Chức năng:
 * - Cung cấp Clock hệ thống chuẩn UTC phục vụ quản lý mốc thời gian đấu giá và quyết toán.
 * - Kích hoạt cơ chế lập lịch @EnableScheduling cho các worker chạy nền.
 * - Cung cấp ThreadPoolTaskScheduler đa luồng để các tác vụ định kỳ (AuctionClosingScheduler,
 *   AuctionOpeningScheduler, AuctionOrderHandoverScheduler) chạy song song độc lập, loại bỏ hiện tượng
 *   nghẽn đơn luồng (single-thread starvation) làm trễ việc chốt phiên khi có retry mạng.
 */
@Configuration
@EnableScheduling
public class AuctionSystemConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Cấu hình ThreadPoolTaskScheduler đa luồng cho background scheduler.
     *
     * Quy tắc và lý do:
     * - Mặc định Spring chỉ cấp 1 thread (pool-size = 1) cho @EnableScheduling. Khi một tác vụ
     *   như AuctionOrderHandoverScheduler thực hiện HTTP retry kéo dài (20s - 45s), AuctionClosingScheduler
     *   bị chặn hoàn toàn, gây ra độ trễ 30s - 1 phút giữa thời gian thực và thời điểm đóng phiên trên DB.
     * - Cấu hình poolSize = 5 đảm bảo tác vụ đóng phiên được thực thi tức thì theo thời gian thực (realtime).
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("auction-scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
