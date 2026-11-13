package com.nexus.commerce.client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Codec JSON Merchant API 2.1.0 theo tài liệu VNPAY QueryDr/Refund: ký chuỗi trường phân cách bằng |.
 * Không dùng quy tắc sort/form-encode của URL PAY. Không gọi mạng, không ghi DB/log secret hoặc chữ ký.
 * Caller phải lưu requestId trước gửi; codec không sinh key, không tự retry và không kết luận Order REFUNDED.
 */
public final class VnpayMerchantProtocol {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final List<String> RESPONSE = List.of("vnp_ResponseId", "vnp_Command", "vnp_ResponseCode",
            "vnp_Message", "vnp_TmnCode", "vnp_TxnRef", "vnp_Amount", "vnp_BankCode", "vnp_PayDate",
            "vnp_TransactionNo", "vnp_TransactionType", "vnp_TransactionStatus", "vnp_OrderInfo");
    private final String merchant;
    private final String secret;

    /** Merchant/secret lấy từ môi trường; thiếu cấu hình từ chối trước khi xây request. */
    public VnpayMerchantProtocol(String merchant, String secret) {
        this.merchant = field(merchant, 8);
        if (!merchant.matches("[A-Za-z0-9]{8}")) { throw invalid(); }
        if (secret == null || secret.isBlank()) { throw invalid(); }
        this.secret = secret;
    }

    /**
     * Xây refund toàn phần type 02 từ snapshot payment và ID đã lưu; amount VND nguyên được nhân 100.
     * originalCreated là lúc tạo giao dịch PAY, không phải ngày nhận IPN; thời gian gửi do caller cung cấp để replay được.
     */
    public Map<String, String> refund(String requestId, String paymentRef, BigDecimal amount, String transactionNo,
            Instant originalCreated, Instant sentAt, String actor, String ip, String description) {
        var params = base(requestId, "refund");
        params.put("vnp_TransactionType", "02");
        params.put("vnp_TxnRef", field(paymentRef, 100));
        params.put("vnp_Amount", amount(amount));
        if (transactionNo == null || !transactionNo.matches("[0-9]{1,15}") || transactionNo.matches("0+")) {
            throw invalid();
        }
        params.put("vnp_TransactionNo", transactionNo);
        params.put("vnp_TransactionDate", date(originalCreated));
        params.put("vnp_CreateBy", field(actor, 245));
        params.put("vnp_CreateDate", date(sentAt));
        params.put("vnp_IpAddr", field(ip, 45));
        params.put("vnp_OrderInfo", field(description, 255));
        params.put("vnp_SecureHash", sign(String.join("|", params.values())));
        return Map.copyOf(params);
    }

    /** Query giao dịch PAY gốc, không coi đây là API tra cứu một refund attempt cụ thể. */
    public Map<String, String> query(String requestId, String paymentRef, Instant originalCreated,
            Instant sentAt, String ip, String description) {
        var params = base(requestId, "querydr");
        params.put("vnp_TxnRef", field(paymentRef, 100));
        params.put("vnp_TransactionDate", date(originalCreated));
        params.put("vnp_CreateDate", date(sentAt));
        params.put("vnp_IpAddr", field(ip, 45));
        params.put("vnp_OrderInfo", field(description, 255));
        params.put("vnp_SecureHash", sign(String.join("|", params.values())));
        return Map.copyOf(params);
    }

    /**
     * Xác minh response đã parse JSON: checksum riêng cho refund/query, command/merchant/ref/amount phải khớp request.
     * Response hợp lệ chỉ có nghĩa đã xác thực nguồn và tương quan, chưa quyết định thành công tài chính.
     * Query có thêm hai trường promotion trong checksum, thiếu trường tùy chọn được biểu diễn bằng chuỗi rỗng.
     */
    public boolean verifies(Map<String, String> response, String command, String paymentRef, BigDecimal expectedAmount) {
        if (response == null || paymentRef == null || !("refund".equals(command) || "querydr".equals(command))) { return false; }
        String hash = response.get("vnp_SecureHash");
        if (hash == null || !hash.matches("[a-fA-F0-9]{128}") || !merchant.equals(response.get("vnp_TmnCode"))
                || !command.equals(response.get("vnp_Command")) || !paymentRef.equals(response.get("vnp_TxnRef"))
                || !amount(expectedAmount).equals(response.get("vnp_Amount"))) { return false; }
        String data = String.join("|", RESPONSE.stream().map(k -> value(response, k)).toList());
        if ("querydr".equals(command)) {
            data += "|" + value(response, "vnp_PromotionCode") + "|" + value(response, "vnp_PromotionAmount");
        }
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(data)), HexFormat.of().parseHex(hash));
    }

    /** RequestId tối đa 32 ký tự chữ/số; caller chịu trách nhiệm uniqueness/persistence. */
    private LinkedHashMap<String, String> base(String id, String command) {
        if (id == null || !id.matches("[A-Za-z0-9]{1,32}")) { throw invalid(); }
        var result = new LinkedHashMap<String, String>();
        result.put("vnp_RequestId", id);
        result.put("vnp_Version", "2.1.0");
        result.put("vnp_Command", command);
        result.put("vnp_TmnCode", merchant);
        return result;
    }

    /** Giới hạn PAY amount 12 chữ số sau nhân 100; không làm tròn hoặc dùng double. */
    private static String amount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 0) { throw invalid(); }
        String result = amount.movePointRight(2).toBigIntegerExact().toString();
        if (result.length() > 12) { throw invalid(); }
        return result;
    }

    /** Chặn ký tự phân cách và điều khiển để không tạo chuỗi checksum mơ hồ; không log giá trị lỗi. */
    private static String field(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit
                || value.chars().anyMatch(c -> c == '|' || Character.isISOControl(c))) { throw invalid(); }
        return value;
    }

    private static String date(Instant value) {
        if (value == null) { throw invalid(); }
        return DATE.format(value);
    }

    private static String value(Map<String, String> values, String key) {
        return values.get(key) == null ? "" : values.get(key);
    }

    /** HMAC-SHA512 UTF-8; không kèm secret/input trong exception hoặc toString. */
    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Không thể ký Merchant API");
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Dữ liệu Merchant API không hợp lệ"); }
}
