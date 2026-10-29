package com.nexus.commerce.client;

import com.nexus.commerce.config.VnpayProperties;
import com.nexus.common.exception.ApiException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** VNPAY PAY 2.1.0: ký URL và xác minh IPN bằng HMAC-SHA512; không ghi log secret hoặc toàn bộ callback. */
@Component
@RequiredArgsConstructor
public class VnpayGateway {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private final VnpayProperties config;

    /** Chặn cấu hình thiếu/sai trước tạo đơn; giới hạn endpoint đúng sandbox để không vô tình chuyển sang tiền thật. */
    public void requireConfigured() {
        if (config.getTmnCode().isBlank() || config.getHashSecret().isBlank() || config.getReturnUrl().isBlank()
                || !"https://sandbox.vnpayment.vn/paymentv2/vpcpay.html".equals(config.getPaymentUrl())) { throw unavailable(); }
        try {
            URI uri = URI.create(config.getReturnUrl());
            if (!uri.isAbsolute() || uri.getHost() == null || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getFragment() != null) { throw unavailable(); }
        } catch (IllegalArgumentException error) { throw unavailable(); }
    }

    /** Số tiền DB là VND nguyên dương; nhân 100 và kiểm tra giới hạn 12 chữ số của PAY, không dùng float/client amount. */
    public String amount(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.stripTrailingZeros().scale() > 0) {
            throw new ApiException("PAYMENT_AMOUNT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "VNPAY yêu cầu số tiền VND nguyên dương", null);
        }
        String scaled = value.movePointRight(2).toBigIntegerExact().toString();
        if (scaled.length() > 12) {
            throw new ApiException("PAYMENT_AMOUNT_INVALID", HttpStatus.UNPROCESSABLE_ENTITY, "Số tiền vượt giới hạn VNPAY", null);
        }
        return scaled;
    }

    /** TxnRef là payment UUID duy nhất, được lưu trước khi trả URL; IP dùng peer của request, không tin X-Forwarded-For tùy ý. */
    public String url(UUID paymentId, BigDecimal total, Instant created, Instant expires, String ip) {
        requireConfigured();
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", config.getTmnCode());
        params.put("vnp_Amount", amount(total));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", paymentId.toString());
        params.put("vnp_OrderInfo", "Thanh toan Nexus " + paymentId);
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", config.getReturnUrl());
        params.put("vnp_IpAddr", ip == null || ip.isBlank() || ip.contains(":") ? "127.0.0.1" : ip);
        params.put("vnp_CreateDate", DATE.format(created));
        params.put("vnp_ExpireDate", DATE.format(expires));
        return config.getPaymentUrl() + "?" + canonical(params) + "&vnp_SecureHash=" + sign(params);
    }

    /** Đầu vào đã decode đúng một lần bởi servlet; loại hash/hashType, sort key và form-encode UTF-8 theo PAY 2.1.0. */
    public String sign(Map<String, String> params) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(config.getHashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(params).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException error) {
            throw new IllegalStateException("Không thể ký VNPAY", error);
        }
    }

    /** So sánh byte hằng thời gian; hash sai định dạng hoặc merchant khác đều không được truy cập dữ liệu thanh toán. */
    public boolean verify(Map<String, String> params) {
        requireConfigured();
        String supplied = params.get("vnp_SecureHash");
        if (supplied == null || !supplied.matches("[a-fA-F0-9]{128}")) { return false; }
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(params)), HexFormat.of().parseHex(supplied))
                && config.getTmnCode().equals(params.get("vnp_TmnCode"));
    }

    private String canonical(Map<String, String> params) {
        return new TreeMap<>(params).entrySet().stream()
                .filter(e -> e.getKey().startsWith("vnp_") && !e.getKey().equals("vnp_SecureHash") && !e.getKey().equals("vnp_SecureHashType"))
                .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private ApiException unavailable() {
        return new ApiException("PAYMENT_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE, "Chưa cấu hình merchant VNPAY sandbox hợp lệ", null);
    }
}
