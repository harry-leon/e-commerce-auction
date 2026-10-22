package com.nexus.user.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bộ lọc đọc thông tin định danh và phân quyền từ HTTP Headers do API Gateway chuyển tiếp.
 * Chức năng:
 * - Tiếp nhận các Header tiêu chuẩn do Gateway đính kèm:
 *   + X-User-Id: Định danh người dùng (Keycloak sub).
 *   + X-User-Roles: Danh sách vai trò (ROLE_BUYER, ROLE_SELLER...).
 *   + X-User-Privileges: Danh sách đặc quyền (PRODUCT.CREATE, USER.VIEW...).
 * - Khởi tạo Authentication và nạp vào SecurityContextHolder của Spring Security.
 * - Cho phép các Controller sử dụng trực tiếp @PreAuthorize("hasAuthority(...)") hoặc @PreAuthorize("hasRole(...)")
 *   mà không cần giải mã lại JWT hay truy vấn cơ sở dữ liệu.
 *

 */
@Component
public class HeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String userId = request.getHeader("X-User-Id");

        if (StringUtils.hasText(userId)) {
            List<GrantedAuthority> authorities = new ArrayList<>();

            // 1. Phân giải danh sách vai trò (Roles)
            String rolesHeader = request.getHeader("X-User-Roles");
            if (StringUtils.hasText(rolesHeader)) {
                String[] roles = rolesHeader.split(",");
                for (String role : roles) {
                    String trimmed = role.trim();
                    if (!trimmed.isEmpty()) {
                        String roleAuthority = trimmed.startsWith("ROLE_") ? trimmed : "ROLE_" + trimmed;
                        authorities.add(new SimpleGrantedAuthority(roleAuthority));
                    }
                }
            }

            // 2. Phân giải danh sách đặc quyền (Privileges)
            String privilegesHeader = request.getHeader("X-User-Privileges");
            if (StringUtils.hasText(privilegesHeader)) {
                String[] privileges = privilegesHeader.split(",");
                for (String privilege : privileges) {
                    String trimmed = privilege.trim();
                    if (!trimmed.isEmpty()) {
                        authorities.add(new SimpleGrantedAuthority(trimmed));
                    }
                }
            }

            // 3. Thiết lập thông tin xác thực vào SecurityContext
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
