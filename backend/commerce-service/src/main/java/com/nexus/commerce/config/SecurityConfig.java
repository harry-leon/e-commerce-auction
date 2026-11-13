package com.nexus.commerce.config;

import com.nexus.common.security.KeycloakAuthorities;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Xác thực JWT Keycloak bằng JWKS/issuer; quyền được đọc từ realm composite thay cho IAM HS256 cũ. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Xác minh JWT stateless trước khi controller chạy; public IPN vẫn được Commerce kiểm tra chữ ký HMAC.
     * Snapshot giao hàng chỉ nhận Fulfillment + COMMERCE.SHIPPING_READ; các namespace nội bộ khác giữ quyền Auction.
     * Không nhận role Admin/browser thay cho service identity khi đọc thông tin người nhận.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/ping").permitAll()
                // Matcher cụ thể phải đứng trước namespace Auction để không mở quyền đọc PII cho Auction hoặc browser.
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/internal/commerce/shipping-orders/*")
                    .access((auth, context) -> new org.springframework.security.authorization.AuthorizationDecision(
                        com.nexus.common.security.ServiceIdentity.permits(auth.get(),
                            "nexus-fulfillment-service", "COMMERCE.SHIPPING_READ")))
                // Phần còn lại của namespace nội bộ chỉ dành cho contract Auction.
                .requestMatchers("/api/v1/internal/commerce/**").access((auth, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        com.nexus.common.security.ServiceIdentity.permits(auth.get(),
                            "nexus-auction-service", "COMMERCE.AUCTION")))
                // Chỉ IPN GET công khai: VNPAY không gửi JWT; chữ ký HMAC được kiểm tra trước mọi cập nhật DB.
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/payments/vnpay/ipn").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    /** Converter chung tránh ROLE_ROLE_ADMIN và nhận privilege từ Keycloak composite. */
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakAuthorities());
        return converter;
    }

    /** JWKS nội bộ dùng để lấy khóa; issuer đối chiếu URL public đã phát token. */
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.keycloak.jwk-set-uri:${KEYCLOAK_URL:http://localhost:8180}/realms/nexus-realm/protocol/openid-connect/certs}") String uri,
            @Value("${app.keycloak.issuer:${KEYCLOAK_ISSUER:http://localhost:8180/realms/nexus-realm}}") String issuer) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(uri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
