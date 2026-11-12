package com.nexus.reputation.config;

import com.nexus.common.security.KeycloakAuthorities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Xác thực JWT Keycloak bằng JWKS/issuer; quyền được đọc từ realm composite thay cho IAM HS256 cũ. */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/ping").permitAll()
                // API trừ điểm uy tín nội bộ bắt buộc token của nexus-commerce-service kèm privilege REPUTATION.PENALTY.
                .requestMatchers("/api/v1/internal/reputations/**").access((auth, context) ->
                    new org.springframework.security.authorization.AuthorizationDecision(
                        com.nexus.common.security.ServiceIdentity.permits(auth.get(),
                            "nexus-commerce-service", "REPUTATION.PENALTY")))
                // Lịch sử xử phạt và hồ sơ /me bắt buộc xác thực JWT trước khi mở rule public cho tóm tắt {userId}.
                .requestMatchers(HttpMethod.GET,
                    "/api/v1/reputations/me", "/api/v1/reputations/*/penalties").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/reputations/*", "/api/v1/ratings/user/*").permitAll()
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
