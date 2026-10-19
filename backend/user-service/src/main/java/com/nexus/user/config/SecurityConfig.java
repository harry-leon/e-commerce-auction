package com.nexus.user.config;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Cấu hình bảo mật ứng dụng với Spring Security và OAuth2 Resource Server.
 * Stateless JWT (HS256). Secret lấy từ biến môi trường JWT_SECRET (>= 32 ký tự).
 * Claim "privileges" được map thẳng thành authority, khớp bảng Privileges của SRS mục 2.7.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Cấu hình SecurityFilterChain phân quyền truy cập các endpoint.
     *
     * @param http Đối tượng HttpSecurity của Spring
     * @return Chuỗi filter đã cấu hình
     * @throws Exception Lỗi cấu hình bảo mật nếu có
     */
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/ping").permitAll()
                .requestMatchers("/api/v1/auth/change-password").authenticated()
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())));
        return http.build();
    }

    /**
     * Bean cung cấp thuật toán băm mật khẩu BCrypt an toàn.
     *
     * @return Đối tượng PasswordEncoder
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Chuyển đổi các claim trong JWT thành tập hợp GrantedAuthority trong Spring Security.
     * Trích xuất claim "privileges" để sử dụng cho @PreAuthorize.
     *
     * @return Converter JwtAuthenticationConverter
     */
    @Bean
    JwtAuthenticationConverter jwtConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("privileges");
        authorities.setAuthorityPrefix("");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        // Giữ các privileges và bổ sung ROLE_<code> từ claim roles để hasAuthority và hasRole cùng hoạt động.
        // Chỉ chuyển đổi claims sau bước xác minh JWT của Resource Server; không tin vai trò do frontend gửi riêng.
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            java.util.Collection<org.springframework.security.core.GrantedAuthority> granted =
                    new java.util.ArrayList<>(authorities.convert(jwt));
            java.util.List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles != null) {
                roles.forEach(role -> granted.add(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role)));
            }
            return granted;
        });
        return converter;
    }

    /**
     * Bean giải mã và xác thực chữ ký của JWT token bằng thuật toán HMAC-SHA256.
     *
     * @param secret Chuỗi secret key từ biến cấu hình
     * @return Đối tượng JwtDecoder
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwt-secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
