package com.nexus.gateway.config;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Đổi cookie access thành Bearer trước Spring Security để xác minh JWT.
 * Request ghi bằng cookie và mọi POST auth phải có header chống CSRF;
 * CORS chỉ mở origin tin cậy; request cookie vẫn phải có header chống CSRF.
 */
@Component
@Order(CookieAuthenticationFilter.BEFORE_SECURITY)
public class CookieAuthenticationFilter implements WebFilter {
    /** Chạy trước WebFilterChainProxy của Spring Security (order -100). */
    public static final int BEFORE_SECURITY = -101;

    /** Bỏ access cookie ở auth để refresh/logout chạy khi access hết hạn. */
    @Override
    public Mono<Void> filter(final ServerWebExchange exchange,
            final WebFilterChain chain) {
        var request = exchange.getRequest();
        String path = request.getURI().getPath();
        boolean auth = path.startsWith("/api/v1/auth/")
            && !path.equals("/api/v1/auth/change-password");
        var access = request.getCookies().getFirst("nexus_access");
        var refresh = request.getCookies().getFirst("nexus_refresh");
        boolean safe = request.getMethod() == HttpMethod.GET
            || request.getMethod() == HttpMethod.HEAD
            || request.getMethod() == HttpMethod.OPTIONS;
        // Header buộc browser preflight; không mở CORS wildcard với cookie.
        if (!safe && (auth || access != null || refresh != null)
                && !"1".equals(request.getHeaders()
                    .getFirst("X-Nexus-Request"))) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        if (!auth && access != null) {
            return chain.filter(exchange.mutate().request(request.mutate()
                .headers(headers -> headers.setBearerAuth(access.getValue()))
                .build()).build());
        }
        return chain.filter(exchange);
    }
}
