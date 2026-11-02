package com.nexus.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

import java.time.Duration;

/**
 * Client BFF gọi IAM/profile qua HTTP; route proxy vẫn dùng Eureka độc lập.
 * Netty giữ timeout 15 giây, không chặn luồng reactive bằng HTTP đồng bộ.
 */
@Configuration
public class ServiceClientConfig {
    /** Giới hạn chờ phản hồi IAM/profile, giữ nguyên timeout 15 giây. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Tạo HTTP client reactive cho các bước điều phối xác thực.
     *
     * @return builder dùng kết nối Netty và timeout hữu hạn
     */
    @Bean
    public WebClient.Builder serviceWebClient() {
        return WebClient.builder()
                .clientConnector(
                        new ReactorClientHttpConnector(
                                HttpClient.create().responseTimeout(
                                    RESPONSE_TIMEOUT)));
    }
}
