package com.nexus.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Cấu hình bảo mật phản ứng (Reactive Security) cho API Gateway.
 * Chức năng:
 * - Bảo vệ điểm vào duy nhất của hệ thống (Single Point of Entry).
 * - Mở công khai các endpoint đăng nhập, làm mới token, đăng ký và tài liệu OpenAPI.
 * - Xác thực chữ ký số JWT với Public Key lấy từ JWKS của Keycloak.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    @Value("${app.keycloak.jwk-set-uri}")
    private String jwkSetUri;

    // Issuer public khác URL JWKS nội bộ trong Compose.
    @Value("${app.keycloak.issuer:http://localhost:8180/realms/nexus-realm}")
    private String issuer;

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(ServerHttpSecurity.CorsSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                // Public health và docs
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .pathMatchers("/api/v1/ping").permitAll()
                // Public auth endpoints
                .pathMatchers("/api/v1/auth/login", "/api/v1/auth/refresh-token", "/api/v1/auth/refresh").permitAll()
                .pathMatchers("/api/v1/auth/register", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                // Public product và category browsing
                .pathMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/categories/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/reputations/**").permitAll()
                // Tất cả yêu cầu còn lại bắt buộc phải có JWT hợp lệ
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder()))
            );

        return http.build();
    }

    /**
     * Tạo ReactiveJwtDecoder sử dụng JWKS URI của Keycloak để giải mã và kiểm tra chữ ký JWT.
     *
     * @return ReactiveJwtDecoder
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
