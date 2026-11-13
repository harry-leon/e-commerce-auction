package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Vector HMAC tính độc lập bằng Python từ contract VNPAY; fixture không dùng credential/merchant thật. */
class VnpayMerchantProtocolTest {
    private final VnpayMerchantProtocol protocol = new VnpayMerchantProtocol("TESTONLY", "fixture-only");
    private final Instant original = Instant.parse("2026-11-13T00:00:00Z");
    private final Instant sent = Instant.parse("2026-11-13T01:00:00Z");

    /** Kiểm tra thứ tự trường, GMT+7 và VND ×100, tránh nhầm với canonical URL PAY. */
    @Test
    void fullRefundMatchesIndependentHmacVector() {
        var request = protocol.refund("req1", "pay1", new BigDecimal("50000"), "12345", original, sent,
                "admin", "127.0.0.1", "Refund test");
        assertEquals("20261113070000", request.get("vnp_TransactionDate"));
        assertEquals("5000000", request.get("vnp_Amount"));
        assertEquals("02", request.get("vnp_TransactionType"));
        assertEquals("ca600aca4b72624f9728c7c094deb5925d433d76e91129d61e060d0fdc37f6422"
                + "99162802196d800dd14a7ae144d9c8a734992f07990e301d0de7fd00664a23e", request.get("vnp_SecureHash"));
    }

    /** Query không đưa TransactionNo/amount vào chuỗi ký của request. */
    @Test
    void paymentQueryMatchesIndependentHmacVector() {
        var request = protocol.query("req2", "pay1", original, sent, "127.0.0.1", "Query test");
        assertEquals("f46aadd34c8cf5c585c1b9d206b5eef38d7cc4f3f8ec04607f7cc7e67e200c7d"
                + "f490d1ab87d1ffbbcd0266ef49dd8f9da9a9c53469813ab0fc2f08ad167ae08e", request.get("vnp_SecureHash"));
    }

    /** Chữ ký đúng vẫn phải gắn đúng command/ref/amount; nội dung đổi hoặc thiếu hash đều bị từ chối. */
    @Test
    void verifiesResponseAndRejectsTamperingOrWrongCorrelation() {
        Map<String, String> response = new HashMap<>(Map.of(
                "vnp_ResponseId", "resp1", "vnp_Command", "refund", "vnp_ResponseCode", "00",
                "vnp_Message", "Success", "vnp_TmnCode", "TESTONLY", "vnp_TxnRef", "pay1",
                "vnp_Amount", "5000000", "vnp_BankCode", "NCB", "vnp_PayDate", "20261113080000"));
        response.putAll(Map.of("vnp_TransactionNo", "67890", "vnp_TransactionType", "02", "vnp_TransactionStatus", "00",
                "vnp_OrderInfo", "Refund test", "vnp_SecureHash",
                "c3f802982496a2c8d9cbee1e631066147f7dc88cee0376c1ee09679c4b763f9c5"
                + "08b24bd38623f1770db0c7a6d1c921fbadd33a9e35f0d323652afc477d7c59d"));
        assertTrue(protocol.verifies(response, "refund", "pay1", new BigDecimal("50000")));
        assertFalse(protocol.verifies(response, "querydr", "pay1", new BigDecimal("50000")));
        assertFalse(protocol.verifies(response, "refund", "other", new BigDecimal("50000")));
        assertFalse(protocol.verifies(response, "refund", "pay1", new BigDecimal("50001")));
        response.put("vnp_TransactionStatus", "05");
        assertFalse(protocol.verifies(response, "refund", "pay1", new BigDecimal("50000")));
    }

    /** Không ký dữ liệu nhập mơ hồ, tiền lẻ hoặc requestId sai định dạng rồi mới mong provider từ chối. */
    @Test
    void rejectsUnsafeOrInvalidRequestsBeforeSigning() {
        assertThrows(IllegalArgumentException.class, () -> protocol.query("bad-key", "pay1", original, sent, "127.0.0.1", "Query"));
        assertThrows(IllegalArgumentException.class, () -> protocol.query("ok", "pay1", original, sent, "127.0.0.1", "a|b"));
        assertThrows(IllegalArgumentException.class, () -> protocol.refund("ok", "pay1", new BigDecimal("0.5"), "1",
                original, sent, "admin", "127.0.0.1", "Refund"));
    }
}
