package com.nexus.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Kiểm thử dịch vụ mã hóa RSA-OAEP SHA-256 tại API Gateway.
 *
 * Chức năng:
 * - Xác minh khóa công khai sinh ra đúng chuẩn X.509 SubjectPublicKeyInfo Base64.
 * - Xác minh giải mã mật khẩu mã hóa từ Frontend bằng chuẩn RSA-OAEP SHA-256.
 * - Xác minh cơ chế tương thích ngược (fallback) khi đầu vào là plain text.
 */
class RsaCryptoServiceTest {

    private RsaCryptoService rsaCryptoService;

    @BeforeEach
    void setUp() {
        rsaCryptoService = new RsaCryptoService();
    }

    /** Kiểm tra Public Key có định dạng Base64 hợp lệ và parse được thành PublicKey chuẩn RSA. */
    @Test
    void publicKeyIsGeneratedAndParsableAsX509() throws Exception {
        String publicKeyBase64 = rsaCryptoService.getPublicKeyBase64();
        assertNotNull(publicKeyBase64);
        assertTrue(publicKeyBase64.length() > 100);

        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey parsedKey = keyFactory.generatePublic(keySpec);
        assertNotNull(parsedKey);
        assertEquals("RSA", parsedKey.getAlgorithm());
    }

    /** Kiểm tra mã hóa từ client bằng RSA-OAEP SHA-256 và Gateway giải mã chính xác mật khẩu gốc. */
    @Test
    void decryptsRsaOaepEncryptedPasswordSuccessfully() throws Exception {
        String originalPassword = "SecureP@ssw0rd2026!";

        // Giả lập Frontend mã hóa bằng Public Key của Gateway
        byte[] keyBytes = Base64.getDecoder().decode(rsaCryptoService.getPublicKeyBase64());
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PublicKey pubKey = keyFactory.generatePublic(new X509EncodedKeySpec(keyBytes));

        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
        cipher.init(Cipher.ENCRYPT_MODE, pubKey, oaepParams);
        byte[] encryptedBytes = cipher.doFinal(originalPassword.getBytes(StandardCharsets.UTF_8));
        String encryptedBase64 = Base64.getEncoder().encodeToString(encryptedBytes);

        // Gateway giải mã
        String decrypted = rsaCryptoService.decrypt(encryptedBase64);
        assertEquals(originalPassword, decrypted);
    }

    /** Kiểm tra cơ chế fallback: chuỗi plain text giữ nguyên để tương thích với test cũ. */
    @Test
    void plainTextPasswordFallsBackGracefully() {
        String plainPassword = "PlainPassword123!";
        String result = rsaCryptoService.decrypt(plainPassword);
        assertEquals(plainPassword, result);
    }

    /** Kiểm tra chuỗi rỗng hoặc null trả về nguyên bản an toàn. */
    @Test
    void nullOrEmptyInputReturnsOriginal() {
        assertEquals("", rsaCryptoService.decrypt(""));
        assertNull(rsaCryptoService.decrypt(null));
    }
}
