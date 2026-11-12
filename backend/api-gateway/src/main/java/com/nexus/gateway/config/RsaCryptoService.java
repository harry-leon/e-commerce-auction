package com.nexus.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Dịch vụ cung cấp cặp khóa RSA và giải mã mật khẩu từ Frontend.
 *
 * Chức năng:
 * 1. Khởi tạo và quản lý cặp khóa RSA 2048-bit tại API Gateway.
 * 2. Cung cấp Public Key (chuẩn X.509 SubjectPublicKeyInfo Base64) cho Frontend qua REST API.
 * 3. Giải mã mật khẩu được Frontend mã hóa bằng chuẩn RSA-OAEP with SHA-256 & MGF1-SHA-256.
 * 4. Hỗ trợ fallback: nếu chuỗi đầu vào không phải là RSA Base64 hợp lệ, trả về nguyên bản
 *    để duy trì tương thích với unit test hoặc plain text request từ các service nội bộ.
 *
 * Luồng xử lý:
 * - Khi Gateway khởi động: sinh ngẫu nhiên cặp khóa RSA 2048-bit.
 * - Khi Frontend gọi GET /api/v1/auth/public-key: trả về Public Key Base64.
 * - Khi Frontend submit form đăng nhập/đăng ký: Gateway nhận chuỗi mã hóa Base64
 *   và dùng Private Key để khôi phục mật khẩu gốc trước khi chuyển tiếp cho Keycloak IAM.
 */
@Service
public class RsaCryptoService {

    private static final Logger LOG = LoggerFactory.getLogger(RsaCryptoService.class);
    private static final String ALGORITHM = "RSA";
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final String publicKeyBase64;

    public RsaCryptoService() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(ALGORITHM);
            kpg.initialize(2048);
            KeyPair keyPair = kpg.generateKeyPair();
            this.publicKey = keyPair.getPublic();
            this.privateKey = keyPair.getPrivate();
            this.publicKeyBase64 = Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
            LOG.info("RsaCryptoService khởi tạo thành công cặp khóa RSA 2048-bit cho Frontend Password Encryption.");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Hệ thống không hỗ trợ thuật toán RSA", e);
        }
    }

    /**
     * Lấy Public Key dạng Base64 (chuẩn DER / X.509 SPKI) để Frontend import vào Web Crypto API.
     *
     * @return chuỗi Base64 của Public Key
     */
    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    /**
     * Giải mã chuỗi mật khẩu được mã hóa từ Frontend bằng RSA-OAEP.
     *
     * @param encryptedBase64 chuỗi Base64 ciphertext từ Frontend
     * @return mật khẩu gốc dạng chuỗi, hoặc chính encryptedBase64 nếu giải mã thất bại (fallback)
     */
    public String decrypt(final String encryptedBase64) {
        if (encryptedBase64 == null || encryptedBase64.isBlank()) {
            return encryptedBase64;
        }

        try {
            byte[] cipherBytes = Base64.getDecoder().decode(encryptedBase64.trim());
            // RSA-2048 sinh ciphertext có kích thước đúng 256 bytes.
            if (cipherBytes.length != 256) {
                // Không phải độ dài chuẩn RSA-2048 -> fallback mật khẩu thông thường.
                return encryptedBase64;
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                    "SHA-256",
                    "MGF1",
                    new MGF1ParameterSpec("SHA-256"),
                    PSource.PSpecified.DEFAULT
            );
            cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepParams);
            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Trường hợp không phải RSA ciphertext (hoặc plain-text từ client cũ): fallback trả về mật khẩu gốc
            LOG.debug("Không thể giải mã RSA (fallback sang plain text): {}", e.getMessage());
            return encryptedBase64;
        }
    }
}
