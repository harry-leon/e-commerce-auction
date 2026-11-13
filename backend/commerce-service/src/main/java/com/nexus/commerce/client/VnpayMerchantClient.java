package com.nexus.commerce.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.commerce.config.VnpayProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HTTP JSON Merchant API sandbox: một lần gửi, timeout hữu hạn, không redirect/retry mutation.
 * Mặc định tắt; RefundDispatchWorker còn cần flag riêng và approval. Caller lưu requestId/PROCESSING trước network.
 * Lỗi mạng/HTTP/checksum được coi là kết quả chưa rõ, không được chuyển FAILED hoặc gửi lại mù.
 */
@Component
public class VnpayMerchantClient {
    private static final URI ENDPOINT = URI.create("https://sandbox.vnpayment.vn/merchant_webapi/api/transaction");
    private final VnpayProperties config;
    private final HttpClient http;

    /** Client JDK không bật log body/form; endpoint cố định sandbox, không cho cấu hình nhầm sang tiền thật. */
    @Autowired
    public VnpayMerchantClient(VnpayProperties config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** Cho test inject HTTP mock; không mở override endpoint hoặc credential test trong cấu hình production. */
    VnpayMerchantClient(VnpayProperties config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * Nhận request đã ký bởi VnpayMerchantProtocol và amount DB; trả trường kết quả đã xác thực, không trả chữ ký/raw JSON.
     * Request phải cùng merchant và command được hỗ trợ; payload lỗi bị chặn trước mạng.
     * Sau khi gửi, mọi lỗi đều ném UncertainOutcomeException không chứa response/credential; caller cần đối soát.
     */
    public Outcome exchange(Map<String, String> request, BigDecimal amount) {
        if (!config.isMerchantApiEnabled()) { throw new IllegalStateException("Merchant API chưa được bật"); }
        var protocol = new VnpayMerchantProtocol(config.getTmnCode(), config.getHashSecret());
        String command = request.get("vnp_Command");
        if (!("refund".equals(command) || "querydr".equals(command))
                || !config.getTmnCode().equals(request.get("vnp_TmnCode"))
                || request.get("vnp_TxnRef") == null || request.get("vnp_SecureHash") == null) {
            throw new IllegalArgumentException("Request Merchant API không hợp lệ");
        }
        String body;
        try { body = new ObjectMapper().writeValueAsString(request); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Không serialize được Merchant request");
        }
        var message = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            var response = http.send(message, HttpResponse.BodyHandlers.ofByteArray());
            // Nhận body trong send để request timeout bao phủ tải body; giới hạn parse sau khi đã nhận byte[].
            // Chưa có streaming limit trước cấp phát; không dùng adapter này cho response lớn.
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes == null || bytes.length > 65536) { throw new UncertainOutcomeException(); }
            Map<String, String> result = new ObjectMapper().readValue(bytes, new TypeReference<Map<String, String>>() { });
            if (!protocol.verifies(result, command, request.get("vnp_TxnRef"), amount)
                    || !twoDigits(result.get("vnp_ResponseCode")) || !twoDigits(result.get("vnp_TransactionStatus"))
                    || ("refund".equals(command) && !"02".equals(result.get("vnp_TransactionType")))) {
                throw new UncertainOutcomeException();
            }
            return new Outcome(result.get("vnp_ResponseCode"), result.get("vnp_TransactionStatus"),
                    result.get("vnp_TransactionType"), result.get("vnp_TransactionNo"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new UncertainOutcomeException();
        } catch (IOException | IllegalArgumentException error) {
            throw new UncertainOutcomeException();
        }
    }

    private static boolean twoDigits(String value) { return value != null && value.matches("[0-9]{2}"); }

    /** Chỉ là thông tin provider trả đã xác thực; worker còn phải mapping trạng thái trước cập nhật tài chính. */
    public record Outcome(String responseCode, String transactionStatus, String transactionType, String providerReference) { }

    /** Kết quả chưa rõ sau khi có thể đã gửi; không đính kèm cause có thể chứa dữ liệu nhạy cảm. */
    public static final class UncertainOutcomeException extends RuntimeException {
        public UncertainOutcomeException() { super("Merchant API chưa rõ kết quả; cần đối soát trước khi gửi lại"); }
    }
}
