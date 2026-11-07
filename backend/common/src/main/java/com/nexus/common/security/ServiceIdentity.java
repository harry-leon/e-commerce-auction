package com.nexus.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Kiểm tra cả client phát token (azp) và privilege, không chấp nhận role người dùng giả làm service. */
public final class ServiceIdentity {
    private ServiceIdentity() { }

    /**
     * Nhận Authentication từ Resource Server đã xác minh chữ ký/issuer/expiry, trả quyết định truy cập nội bộ.
     * Bắt buộc cả azp (client phát token) và privilege; quyền admin hoặc privilege gắn vào client khác không đủ.
     * Không giải mã JWT lần nữa, không tin header X-User-* hay identity do request body cung cấp.
     */
    public static boolean permits(Authentication authentication, String client, String privilege) {
        return authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()
                && client.equals(jwt.getToken().getClaimAsString("azp"))
                && jwt.getAuthorities().stream().anyMatch(a -> privilege.equals(a.getAuthority()));
    }
}
