package com.nexus.user.config;

import com.nexus.common.security.KeycloakAuthorities;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/** Xác thực JWT Keycloak tại service; header X-User-* không tạo Authentication và không thay thế chữ ký. */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    /** Profile creation bắt buộc service account; các API tự phục vụ dùng principal Jwt thực đã xác minh. */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**",
                                "/swagger-ui/**", "/swagger-ui.html", "/api/v1/ping",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    /** Dùng chung chuẩn ROLE_* và privilege composite trên tất cả domain services. */
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakAuthorities());
        return converter;
    }

    /** JWKS có thể là hostname nội bộ, issuer phải đúng URL public nằm trong token. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.keycloak.jwk-set-uri:http://localhost:8180/realms/nexus-realm/protocol/openid-connect/certs}") String uri,
                          @Value("${app.keycloak.issuer:http://localhost:8180/realms/nexus-realm}") String issuer) {
        var decoder = NimbusJwtDecoder.withJwkSetUri(uri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }
}
