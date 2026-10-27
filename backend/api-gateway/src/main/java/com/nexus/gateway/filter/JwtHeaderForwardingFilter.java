package com.nexus.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Xóa header định danh client gửi, gắn lại sub/email từ JWT đã xác minh.
 * Chain chạy đúng một lần; không switchIfEmpty trên Mono<Void> vì response luôn hoàn tất rỗng.
 * Backend tự xác minh Bearer JWT và không dùng các header này thay chữ ký.
 */
@Component
public class JwtHeaderForwardingFilter implements GlobalFilter, Ordered {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var clean = exchange.getRequest().mutate().headers(headers ->
                headers.keySet().removeIf(name -> name.regionMatches(true, 0, "X-User-", 0, 7)
                        || name.equalsIgnoreCase("X-Profile-Subject"))).build();
        ServerWebExchange sanitized = exchange.mutate().request(clean).build();
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(authentication -> authentication instanceof JwtAuthenticationToken)
                .map(authentication -> ((JwtAuthenticationToken) authentication).getToken())
                .map(jwt -> sanitized.mutate().request(clean.mutate().headers(headers -> {
                    headers.set("X-User-Id", jwt.getSubject());
                    if (jwt.getClaimAsString("email") != null) { headers.set("X-User-Email", jwt.getClaimAsString("email")); }
                }).build()).build())
                .defaultIfEmpty(sanitized)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() { return -1; }
}
