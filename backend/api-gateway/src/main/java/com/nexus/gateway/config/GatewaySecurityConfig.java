package com.nexus.gateway.config;

import java.util.Arrays;
import java.util.List;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Bảo vệ HTTP tại Gateway bằng JWT Keycloak và CORS theo origin được cấu hình.
 * Luồng: CookieAuthenticationFilter đổi cookie thành bearer → chain xác minh JWKS/issuer/thời hạn
 * → chỉ các route public được bỏ qua JWT → Gateway chuyển tiếp request đã làm sạch header định danh.
 * Hai endpoint WS chỉ mở transport; service đích xác minh CONNECT và từng frame.
 * CORS không thay thế CSRF: request ghi dùng cookie vẫn phải có X-Nexus-Request: 1.
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

    /** Origin frontend chính xác lấy từ môi trường; proxy cùng origin vẫn hoạt động khi không cần preflight. */
    @Value("${app.web.allowed-origins:http://localhost:3000,http://localhost:5173,http://localhost:8080}")
    private String allowedOrigins;

    /**
     * Đăng ký route công khai và bắt JWT cho các route còn lại.
     *
     * @param http cấu hình security reactive do Spring cấp
     * @return filter chain xác minh chữ ký và issuer
     */
    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            final ServerHttpSecurity http) {
        // CookieAuthenticationFilter kiểm tra header chống CSRF trước chain.
        http.csrf(ServerHttpSecurity.CsrfSpec::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeExchange(exchanges -> exchanges
                // Public health và tài liệu API.
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/v3/api-docs/**", "/swagger-ui/**",
                    "/swagger-ui.html").permitAll()
                .pathMatchers("/api/v1/ping").permitAll()
                // Service đích xác minh CONNECT trước SEND/SUBSCRIBE.
                .pathMatchers(HttpMethod.GET, "/ws/auctions",
                    "/ws/notifications").permitAll()
                // VNPAY dùng HMAC tại Commerce, các API khác vẫn cần JWT.
                .pathMatchers(HttpMethod.GET,
                    "/api/v1/payments/vnpay/ipn").permitAll()
                .pathMatchers("/api/v1/auth/login",
                    "/api/v1/auth/logout",
                    "/api/v1/auth/refresh-token", "/api/v1/auth/refresh")
                    .permitAll()
                .pathMatchers("/api/v1/auth/register",
                    "/api/v1/auth/forgot-password",
                    "/api/v1/auth/reset-password").permitAll()
                .pathMatchers(HttpMethod.GET, "/api/v1/products/**",
                    "/api/v1/categories/**").permitAll()
                // Mở API GET danh sách và chi tiết đấu giá công khai cho khách xem phiên không cần token.
                // Các thao tác nhạy cảm (tạo phiên, sửa, hủy, đặt giá bid) sử dụng POST/PUT vẫn yêu cầu JWT hợp lệ
                // thông qua rule anyExchange().authenticated() của Gateway và @PreAuthorize tại Auction Service.
                .pathMatchers(HttpMethod.GET, "/api/v1/auctions/**")
                    .permitAll()
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

    /**
     * Tạo chính sách preflight từ danh sách origin phân tách bằng dấu phẩy, áp dụng cho toàn bộ HTTP API.
     * Cho credential và các header nghiệp vụ cần thiết; origin ngoài danh sách bị trả 403 trước khi proxy.
     * Không mở wildcard hoặc bỏ header CSRF chỉ để làm request khác origin thành công.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        var origins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).toList();
        // Fail khi boot nếu cấu hình vô tình mở wildcard với cookie, thay vì chờ lỗi preflight lúc người dùng gọi API.
        if (origins.isEmpty() || origins.stream().anyMatch(origin -> origin.isBlank() || origin.contains("*"))) {
            throw new IllegalArgumentException("CORS yêu cầu origin cụ thể khi dùng cookie");
        }
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Nexus-Request", "Idempotency-Key"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
