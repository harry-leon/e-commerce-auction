package com.nexus.catalog.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Hồi quy phân quyền: token phải chuyển được cả privilege trực tiếp và vai trò ADMIN thành Spring authorities. */
class JwtAuthoritiesTest {
    @Test
    void mapsBothRoleClaimsAndPrivileges() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "HS256").subject("user")
                .claim("roles", List.of("ADMIN"))
                .claim("privileges", List.of("PRODUCT.UPDATE")).build();
        var authentication = new SecurityConfig().jwtConverter().convert(jwt);
        var names = authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList();
        assertTrue(names.contains("ROLE_ADMIN"));
        assertTrue(names.contains("PRODUCT.UPDATE"));
    }
}
