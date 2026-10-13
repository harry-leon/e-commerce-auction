package com.nexus.user.service;

import com.nexus.user.entity.Privilege;
import com.nexus.user.entity.Role;
import com.nexus.user.entity.User;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service xử lý tạo (generate), ký và giải mã JWT token (Access Token và Refresh Token).
 * Nhúng danh sách claim "privileges" khớp bảng Privileges của SRS mục 2.7.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Service
public class JwtService {

    private final byte[] secretKey;
    private final long accessTokenExpiryMinutes;
    private final long refreshTokenExpiryDays;

    /**
     * Khởi tạo JwtService với cấu hình từ file application.yml.
     *
     * @param secretKey                Chuỗi bí mật ký HMAC-SHA256 (tối thiểu 32 ký tự)
     * @param accessTokenExpiryMinutes Thời gian hết hạn của Access Token (phút)
     * @param refreshTokenExpiryDays   Thời gian hết hạn của Refresh Token (ngày)
     */
    public JwtService(
            @Value("${app.security.jwt-secret}") String secretKey,
            @Value("${app.security.access-token-expiry-minutes:60}") long accessTokenExpiryMinutes,
            @Value("${app.security.refresh-token-expiry-days:7}") long refreshTokenExpiryDays) {
        this.secretKey = secretKey.getBytes(StandardCharsets.UTF_8);
        this.accessTokenExpiryMinutes = accessTokenExpiryMinutes;
        this.refreshTokenExpiryDays = refreshTokenExpiryDays;
    }

    /**
     * Tạo Access Token chứa thông tin người dùng và danh sách đặc quyền (privileges).
     *
     * @param user Đối tượng người dùng
     * @return Chuỗi JWT Access Token đã ký
     */
    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(accessTokenExpiryMinutes, ChronoUnit.MINUTES);

        Set<String> roleCodes = user.getRoles().stream()
                .map(Role::getCode)
                .collect(Collectors.toSet());

        Set<String> privilegeCodes = new HashSet<>();
        for (Role role : user.getRoles()) {
            if (role.getPrivileges() != null) {
                for (Privilege p : role.getPrivileges()) {
                    privilegeCodes.add(p.getCode());
                }
            }
        }

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer("nexus-user-service")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .claim("email", user.getEmail())
                .claim("roles", roleCodes)
                .claim("privileges", privilegeCodes)
                .claim("token_version", user.getTokenVersion())
                .claim("token_type", "ACCESS")
                .build();

        return signJwt(claimsSet);
    }

    /**
     * Tạo Refresh Token dài hạn dùng để xin cấp lại Access Token.
     *
     * @param user Đối tượng người dùng
     * @return Chuỗi JWT Refresh Token đã ký
     */
    public String generateRefreshToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(refreshTokenExpiryDays, ChronoUnit.DAYS);

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(user.getId().toString())
                .issuer("nexus-user-service")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expiry))
                .claim("email", user.getEmail())
                .claim("token_version", user.getTokenVersion())
                .claim("token_type", "REFRESH")
                .build();

        return signJwt(claimsSet);
    }

    /**
     * Giải mã và trích xuất claims từ JWT string.
     *
     * @param token Chuỗi JWT token
     * @return JWTClaimsSet chứa các claims đã giải mã
     * @throws ParseException Nếu định dạng token không hợp lệ
     */
    public JWTClaimsSet extractClaims(String token) throws ParseException {
        SignedJWT signedJwt = SignedJWT.parse(token);
        return signedJwt.getJWTClaimsSet();
    }

    /**
     * Trích xuất mã người dùng (UserId) từ JWT.
     *
     * @param token Chuỗi token
     * @return UUID của người dùng
     */
    public UUID extractUserId(String token) {
        try {
            JWTClaimsSet claims = extractClaims(token);
            return UUID.fromString(claims.getSubject());
        } catch (ParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Token không hợp lệ", e);
        }
    }

    /**
     * Lấy thời gian sống của Access Token tính bằng giây.
     *
     * @return Số giây hiệu lực của Access Token
     */
    public long getAccessTokenExpirySeconds() {
        return accessTokenExpiryMinutes * 60;
    }

    /**
     * Ký JWT bằng thuật toán HMAC-SHA256 với secret key.
     *
     * @param claimsSet Tập hợp các claims
     * @return Chuỗi JWT đã được ký
     */
    private String signJwt(JWTClaimsSet claimsSet) {
        try {
            SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claimsSet);
            signedJwt.sign(new MACSigner(secretKey));
            return signedJwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Lỗi trong quá trình ký JWT token", e);
        }
    }
}
