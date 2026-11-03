package com.nexus.common.config;

import com.nexus.common.exception.GlobalExceptionHandler;
import com.nexus.common.filter.RequestIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Lớp tự động cấu hình (AutoConfiguration) của module nexus-common.
 * Tự động đăng ký GlobalExceptionHandler và RequestIdFilter cho bất kỳ microservice nào
 * phụ thuộc vào module này khi chạy trong môi trường Servlet Web.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RequestIdFilter requestIdFilter() {
        return new RequestIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }
}
