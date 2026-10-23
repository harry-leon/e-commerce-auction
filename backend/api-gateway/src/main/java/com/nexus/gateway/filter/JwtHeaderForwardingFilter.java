package com.nexus.gateway.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Bộ lọc toàn cục chuyển tiếp thông tin định danh từ JWT sang HTTP Headers.
 * Chức năng:
 * - Sau khi Gateway xác thực JWT thành công, bộ lọc này trích xuất thông tin người dùng:
 *   + "sub" (User ID) -> Header "X-User-Id"
 *   + "roles" / "realm_access.roles" -> Header "X-User-Roles"
 *   + "privileges" -> Header "X-User-Privileges"
 *   + "email" -> Header "X-User-Email"
 * - Giúp các microservice phía sau (user-service, commerce-service...) đọc trực tiếp định danh
 *   mà không cần giải mã lại JWT hay truy vấn database, đảm bảo kiến trúc Stateless & Header Forwarding.
 *

 */
@Component
public class JwtHeaderForwardingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(auth -> auth instanceof JwtAuthenticationToken)
            .cast(JwtAuthenticationToken.class)
            .map(JwtAuthenticationToken::getToken)
            .flatMap(jwt -> {
                ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

                // 1. Trích xuất User ID (Subject)
                String userId = jwt.getSubject();
                if (userId != null) {
                    requestBuilder.header("X-User-Id", userId);
                }

                // 2. Trích xuất Email
                String email = jwt.getClaimAsString("email");
                if (email != null) {
                    requestBuilder.header("X-User-Email", email);
                }

                // 3. Trích xuất Roles (hỗ trợ cả claim trực tiếp 'roles' và cấu trúc 'realm_access.roles' của Keycloak)
                List<String> roles = extractRoles(jwt);
                if (!roles.isEmpty()) {
                    requestBuilder.header("X-User-Roles", String.join(",", roles));
                }

                // 4. Trích xuất Privileges (nếu có claim 'privileges')
                List<String> privileges = jwt.getClaimAsStringList("privileges");
                if (privileges != null && !privileges.isEmpty()) {
                    requestBuilder.header("X-User-Privileges", String.join(",", privileges));
                }

                ServerHttpRequest mutatedRequest = requestBuilder.build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            })
            .switchIfEmpty(chain.filter(exchange));
    }

    private List<String> extractRoles(Jwt jwt) {
        List<String> roles = new ArrayList<>();

        // Kiểm tra claim realm_access của Keycloak
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof Collection<?> rawRoles) {
            for (Object role : rawRoles) {
                if (role != null) {
                    roles.add(role.toString());
                }
            }
        }

        // Kiểm tra claim trực tiếp "roles"
        List<String> directRoles = jwt.getClaimAsStringList("roles");
        if (directRoles != null) {
            for (String r : directRoles) {
                if (!roles.contains(r)) {
                    roles.add(r);
                }
            }
        }

        return roles;
    }

    @Override
    public int getOrder() {
        // Chạy ngay sau khi SecurityContext được nạp
        return 0;
    }
}
