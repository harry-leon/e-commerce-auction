package com.nexus.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Chuẩn hóa claims đã xác minh: roles/realm_access.roles thành ROLE_*, privilege thành DOMAIN.ACTION.
 * Không tự cấp quyền theo tên role; Keycloak composite quyết định quyền và mở rộng vào token.
 */
public class KeycloakAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> names = new LinkedHashSet<>();
        add(names, jwt.getClaim("roles"), true);
        Map<String, Object> realm = jwt.getClaim("realm_access");
        if (realm != null) { add(names, realm.get("roles"), true); }
        add(names, jwt.getClaim("privileges"), false);
        return names.stream().map(name -> (GrantedAuthority) new SimpleGrantedAuthority(name)).toList();
    }

    /** Chỉ nhận danh sách string; bỏ role hệ thống Keycloak không thuộc namespace ứng dụng. */
    private void add(Set<String> target, Object claim, boolean role) {
        if (claim instanceof Collection<?> values) {
            for (Object value : values) {
                if (value instanceof String name && !name.isBlank()) {
                    if (!role || name.contains(".") || name.startsWith("ROLE_")) { target.add(name); }
                    else if (name.matches("[A-Z][A-Z0-9_]*")) { target.add("ROLE_" + name); }
                }
            }
        }
    }
}
