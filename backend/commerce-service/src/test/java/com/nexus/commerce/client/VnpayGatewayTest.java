package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.commerce.config.VnpayProperties;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Bảo vệ canonical encoding, ký/xác minh checksum và giới hạn VND; toàn bộ merchant/secret ở đây chỉ là fixture test. */
class VnpayGatewayTest {
    private final VnpayProperties config = new VnpayProperties();
    private VnpayGateway gateway;

    /** Vector Python hmac/hashlib độc lập, tránh test round-trip che lỗi sort/form-encode/ký. */
    @Test
    void matchesIndependentHmacGoldenVector() {
        var params = Map.of("vnp_Amount", "5000000", "vnp_OrderInfo", "Thanh toan Nexus + &", "vnp_TmnCode", "TESTONLY");
        assertEquals("c06c3673f8db5ed26d529cb3854bd1cbdaa8afe1468660a39c166b16bdb5e56f7f32e4fccdb1a897edc8f2948d7558a2404c84a29874eef09dbedb370521ebc6",
                gateway.sign(params));
    }

    @BeforeEach
    void setup() {
        config.setTmnCode("TESTONLY");
        config.setHashSecret("unit-test-only-not-a-credential");
        config.setReturnUrl("https://example.test/payment-result");
        gateway = new VnpayGateway(config);
    }

    /** Số tiền 50.000 VND phải thành 5.000.000; date là GMT+7, URL ký được kiểm tra lại độc lập với thứ tự query. */
    @Test
    void signsSandboxUrlWithAmountAndVietnamTime() {
        String url = gateway.url(UUID.randomUUID(), new BigDecimal("50000.00"), Instant.parse("2026-10-29T00:00:00Z"),
                Instant.parse("2026-10-29T00:15:00Z"), "192.0.2.1");
        assertTrue(url.startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        Map<String, String> params = new HashMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        assertEquals("5000000", params.get("vnp_Amount"));
        assertEquals("20261029070000", params.get("vnp_CreateDate"));
        assertEquals("20261029071500", params.get("vnp_ExpireDate"));
        assertTrue(gateway.verify(params));
        params.put("vnp_Amount", "1");
        assertFalse(gateway.verify(params));
    }

    /** Không được chấp nhận hash thiếu/sai dạng hoặc merchant khác dù chữ ký của merchant test hợp lệ. */
    @Test
    void rejectsMissingHashAndWrongMerchant() {
        var params = new HashMap<>(Map.of("vnp_TmnCode", "OTHER", "vnp_OrderInfo", "Thanh toán + &"));
        assertFalse(gateway.verify(params));
        params.put("vnp_SecureHash", "invalid");
        assertFalse(gateway.verify(params));
        params.put("vnp_SecureHash", gateway.sign(params));
        assertFalse(gateway.verify(params));
        params.put("vnp_TmnCode", "TESTONLY");
        params.put("vnp_SecureHash", gateway.sign(params).toUpperCase());
        assertTrue(gateway.verify(params));
    }

    /** Chưa cấu hình merchant và số tiền âm/lẻ/quá lớn không được tạo URL thanh toán. */
    @Test
    void rejectsInvalidConfigurationAndAmounts() {
        assertThrows(ApiException.class, () -> gateway.amount(new BigDecimal("1.25")));
        assertThrows(ApiException.class, () -> gateway.amount(BigDecimal.ZERO));
        assertThrows(ApiException.class, () -> gateway.amount(new BigDecimal("10000000000")));
        config.setReturnUrl("javascript:alert(1)");
        assertThrows(ApiException.class, gateway::requireConfigured);
        config.setReturnUrl("https://example.test/payment-result");
        config.setPaymentUrl("https://pay.vnpay.vn/vpcpay.html");
        assertThrows(ApiException.class, gateway::requireConfigured);
        config.setHashSecret("");
        assertThrows(ApiException.class, gateway::requireConfigured);
    }
}
