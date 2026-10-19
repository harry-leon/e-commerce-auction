package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;

/** Hồi quy bảo mật: JWT giả chữ ký hoặc đã hết hạn không được đọc claims như token hợp lệ. */
class JwtSignatureTest {
    private static final String SECRET = "test-secret-test-secret-test-secret-123456";
    private final JwtService service = new JwtService(SECRET, 60, 7);

    private String token(String key, Instant expires) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .subject("user").issuer("nexus-user-service").expirationTime(Date.from(expires)).build());
        jwt.sign(new MACSigner(key));
        return jwt.serialize();
    }

    @Test
    void rejectsForgedRefreshToken() throws Exception {
        String forged = token("different-secret-different-secret-123456", Instant.now().plusSeconds(3600));
        assertThrows(IllegalArgumentException.class, () -> service.extractClaims(forged));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        String expired = token(SECRET, Instant.now().minusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> service.extractClaims(expired));
    }
}
