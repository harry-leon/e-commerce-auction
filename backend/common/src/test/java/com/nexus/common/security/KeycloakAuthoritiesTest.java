package com.nexus.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Hồi quy ROLE_ROLE_ADMIN và privilege composite: chỉ claims đã ký mới sinh authority. */
class KeycloakAuthoritiesTest {
    @Test
    void normalizesAndDeduplicatesRealmAndLegacyClaims() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("subject")
                .claim("roles", List.of("ADMIN", "ROLE_ADMIN"))
                .claim("realm_access", Map.of("roles", List.of("ROLE_BUYER", "PRODUCT.CREATE", "offline_access")))
                .claim("privileges", List.of("PRODUCT.CREATE", "PROFILE.VIEW")).build();
        assertEquals(Set.of("ROLE_ADMIN", "ROLE_BUYER", "PRODUCT.CREATE", "PROFILE.VIEW"),
                new KeycloakAuthorities().convert(jwt).stream().map(item -> item.getAuthority()).collect(Collectors.toSet()));
    }

    @Test
    void missingOrMalformedClaimsGrantNothing() {
        Jwt jwt = Jwt.withTokenValue("test").header("alg", "RS256").subject("subject").claim("roles", "ADMIN").build();
        assertEquals(0, new KeycloakAuthorities().convert(jwt).size());
    }
}
