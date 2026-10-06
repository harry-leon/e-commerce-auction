package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.user.entity.Privilege;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử đơn vị cho JwtService.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
class JwtServiceTest {

    private JwtService jwtService;
    private User testUser;

    @BeforeEach
    void setUp() {
        String testSecret = "nexus-test-secret-key-at-least-32-characters-long!";
        jwtService = new JwtService(testSecret, 60, 7);

        testUser = new User();
        testUser.setId(UUID.randomUUID());
        testUser.setEmail("buyer@nexus.com");
        testUser.setFullName("Nguyen Van A");
        testUser.setTokenVersion(1);

        Role role = new Role(UUID.randomUUID(), "BUYER", "Buyer Role", "Desc");
        Privilege p1 = new Privilege(UUID.randomUUID(), "AUTH.LOGIN", "Login");
        Privilege p2 = new Privilege(UUID.randomUUID(), "PRODUCT.VIEW", "View product");
        role.addPrivilege(p1);
        role.addPrivilege(p2);
        testUser.addRole(role);
    }

    @Test
    void testGenerateAndExtractAccessToken() throws Exception {
        String token = jwtService.generateAccessToken(testUser);
        assertNotNull(token);

        JWTClaimsSet claims = jwtService.extractClaims(token);
        assertEquals(testUser.getId().toString(), claims.getSubject());
        assertEquals("buyer@nexus.com", claims.getStringClaim("email"));
        assertEquals("ACCESS", claims.getStringClaim("token_type"));
        assertEquals(1, claims.getLongClaim("token_version"));

        List<String> privileges = claims.getStringListClaim("privileges");
        assertNotNull(privileges);
        assertTrue(privileges.contains("AUTH.LOGIN"));
        assertTrue(privileges.contains("PRODUCT.VIEW"));
    }

    @Test
    void testGenerateRefreshToken() throws Exception {
        String refreshToken = jwtService.generateRefreshToken(testUser);
        assertNotNull(refreshToken);

        JWTClaimsSet claims = jwtService.extractClaims(refreshToken);
        assertEquals(testUser.getId().toString(), claims.getSubject());
        assertEquals("REFRESH", claims.getStringClaim("token_type"));
    }

    @Test
    void testExtractUserId() {
        String token = jwtService.generateAccessToken(testUser);
        UUID extractedId = jwtService.extractUserId(token);
        assertEquals(testUser.getId(), extractedId);
    }
}
