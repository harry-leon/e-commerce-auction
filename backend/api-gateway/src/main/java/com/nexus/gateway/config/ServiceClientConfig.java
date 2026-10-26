package com.nexus.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/** Client BFF gọi IAM/profile bằng URL HTTP riêng; Gateway routes vẫn dùng Eureka độc lập. */
@Configuration
public class ServiceClientConfig {
    @Bean
    public WebClient.Builder serviceWebClient() {
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(java.time.Duration.ofSeconds(15))));
    }
}
