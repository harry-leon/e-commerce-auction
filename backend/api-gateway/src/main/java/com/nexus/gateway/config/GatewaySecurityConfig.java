package com.nexus.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Cấu hình bảo mật phản ứng (Reactive Security) cho API Gateway. Chức năng: -
 * Bảo vệ điểm vào duy
 * nhất của hệ thống (Single Point of Entry). - Mở công khai các endpoint đăng
 * nhập, làm mới token,
 * đăng ký và tài liệu OpenAPI. - Xác thực chữ ký số JWT với Public Key lấy từ
 * JWKS của Keycloak.
 */
@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {

    /** JWKS endpoint dùng lấy khóa công khai để xác minh JWT. */
    @Value("${app.keycloak.jwk-set-uri}")
    private String jwkSetUri;

    /** Issuer public khác URL JWKS nội bộ trong Compose. */
    @Value("${app.keycloak.issuer:http://localhost:8180/realms/nexus-realm}")
    private String issuer;

    /**
     * Đăng ký route công khai và bắt JWT cho các route còn lại.
     *
     * @param http cấu hình security reactive do Spring cấp
     * @return filter chain xác minh chữ ký và issuer
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            final ServerHttpSecurity http) {
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(ServerHttpSecurity.CorsSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                // Public health và tài liệu API.
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**",
                    "/swagger-ui.html").permitAll()
                .pathMatchers("/api/v1/ping").permitAll()
                .pathMatchers("/api/v1/auth/login",
                    "/api/v1/auth/refresh-token", "/api/v1/auth/refresh")
                    .permitAll()
                .pathMatchers("/api/v1/auth/register",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/products/**",
                    "/api/v1/categories/**").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/reputations/**")
                    .permitAll()
                .anyExchange().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder())));
        return http.build();
    }

    /**
     * Tạo ReactiveJwtDecoder sử dụng JWKS URI của Keycloak để giải mã và kiểm
     * tra chữ ký JWT.
     *
     * @return ReactiveJwtDecoder
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
