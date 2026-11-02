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
 * Xóa header định danh client gửi, gắn sub/email từ JWT đã xác minh.
 * Chain chạy một lần; không switchIfEmpty trên tín hiệu hoàn tất rỗng.
 * Backend tự xác minh JWT, không dùng header này thay chữ ký.
 */
@Component
public class JwtHeaderForwardingFilter implements GlobalFilter, Ordered {
    /** Tiền tố header định danh không được tin từ client. */
    private static final String USER_HEADER_PREFIX = "X-User-";

    /**
     * Xóa header giả mạo và chuyển tiếp claim từ JWT đã xác minh.
     *
     * @param exchange request/response reactive hiện tại
     * @param chain chuỗi filter downstream, được gọi đúng một lần
     * @return tín hiệu hoàn tất request; không tạo session hay token mới
     */
    @Override
    public Mono<Void> filter(
            final ServerWebExchange exchange, final GatewayFilterChain chain) {
        var clean = exchange.getRequest().mutate().headers(headers -> {
            headers.keySet().removeIf(name -> name.regionMatches(true, 0,
                    USER_HEADER_PREFIX, 0, USER_HEADER_PREFIX.length())
                    || name.equalsIgnoreCase("X-Profile-Subject"));
        }).build();
        ServerWebExchange sanitized = exchange.mutate().request(clean).build();
        return ReactiveSecurityContextHolder.getContext()
                .map(context -> context.getAuthentication())
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .map(auth -> ((JwtAuthenticationToken) auth).getToken())
                .map(jwt -> sanitized.mutate().request(clean.mutate()
                    .headers(headers -> {
                        headers.set("X-User-Id", jwt.getSubject());
                        if (jwt.getClaimAsString("email") != null) {
                            headers.set("X-User-Email",
                                    jwt.getClaimAsString("email"));
                        }
                    }).build()).build())
                .defaultIfEmpty(sanitized)
                .flatMap(chain::filter);
    }

    /**
     * Chạy sớm để header client được làm sạch trước proxy downstream.
     *
     * @return độ ưu tiên -1 giữ nguyên thứ tự hiện có
     */
    @Override
    public int getOrder() {
        return -1;
    }
}
