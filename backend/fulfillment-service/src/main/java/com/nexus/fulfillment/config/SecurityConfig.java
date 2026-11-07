package com.nexus.fulfillment.config;

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
     * Kiểm tra JWT rồi phân tách caller theo tác vụ: Auction quản lý giữ kho phiên, Commerce quản lý kho đơn hàng.
     * Kiểm tra đồng thời azp/privilege ngay tại filter, trước cả validation body hoặc gọi InventoryService.
     * GET stock/check availability tiếp tục public; quyền inbound/adjust/ledger được kiểm tra tại Controller.
     * User/admin hoặc service khác bị 403, request chưa xác thực bị 401; không tin header định danh từ Gateway.
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/ping").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/inventory/sku/**").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/inventory/check").permitAll()
                // API thay đổi giữ kho chỉ nhận service token đúng azp + privilege; JWT buyer/seller không đủ quyền.
                .requestMatchers("/api/v1/inventory/auctions/**").access((auth, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        com.nexus.common.security.ServiceIdentity.permits(auth.get(),
                            "nexus-auction-service", "INVENTORY.AUCTION")))
                .requestMatchers("/api/v1/inventory/reserve", "/api/v1/inventory/release",
                    "/api/v1/inventory/reservations/*/confirm", "/api/v1/inventory/reservations/*/release",
                    "/api/v1/inventory/orders/**", "/api/v1/inventory/events/**").access((auth, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        com.nexus.common.security.ServiceIdentity.permits(auth.get(),
                            "nexus-commerce-service", "INVENTORY.ORDER")))
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
