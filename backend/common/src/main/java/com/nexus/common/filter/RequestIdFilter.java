package com.nexus.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filter chặn các HTTP request đến để quản lý đồng bộ Header X-Request-Id.
 * - Đọc X-Request-Id từ request (nếu có) hoặc tự sinh mã định danh truy vết mới.
 * - Lưu vào RequestIdContext và SLF4J MDC để tự động gắn vào log của hệ thống.
 * - Đính kèm X-Request-Id vào HTTP response header để client/frontend tiện debug.
 * - Dọn dẹp context sau khi kết thúc request để tránh rò rỉ luồng trong ThreadPool.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = RequestIdContext.generate();
        }

        RequestIdContext.set(requestId);
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            RequestIdContext.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
