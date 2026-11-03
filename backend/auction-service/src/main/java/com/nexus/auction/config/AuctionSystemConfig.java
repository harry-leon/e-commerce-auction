package com.nexus.auction.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cấu hình các Bean hệ thống dùng chung cho auction-service.
 *
 * Chức năng:
 * - Cung cấp Clock hệ thống chuẩn UTC phục vụ quản lý mốc thời gian đấu giá và quyết toán.
 * - Kích hoạt cơ chế lập lịch @EnableScheduling cho các worker chạy nền.
 */
@Configuration
@EnableScheduling
public class AuctionSystemConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

