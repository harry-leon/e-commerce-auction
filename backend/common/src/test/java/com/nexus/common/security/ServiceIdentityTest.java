package com.nexus.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** JWT đúng role nhưng sai azp vẫn phải bị chặn, tránh dùng tài khoản người dùng/admin giả service. */
class ServiceIdentityTest {
    @Test
    void requiresMatchingClientAndPrivilege() {
        var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject("service")
                .claim("azp", "nexus-auction-service").build();
        var auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("INVENTORY.AUCTION")));
        assertTrue(ServiceIdentity.permits(auth, "nexus-auction-service", "INVENTORY.AUCTION"));
        assertFalse(ServiceIdentity.permits(auth, "nexus-commerce-service", "INVENTORY.AUCTION"));
        assertFalse(ServiceIdentity.permits(auth, "nexus-auction-service", "INVENTORY.ORDER"));
        assertFalse(ServiceIdentity.permits(null, "nexus-auction-service", "INVENTORY.AUCTION"));
    }
}
