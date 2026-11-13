package com.nexus.commerce.client;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.nexus.commerce.config.VnpayProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** HTTP mock cô lập hoàn toàn VNPAY: không có merchant thật, không gửi refund hoặc query ra Internet. */
class VnpayMerchantClientTest {
    /** Mặc định tắt chặn trước cả network, kể cả caller đã có request ký hợp lệ. */
    @Test
    void disabledClientNeverTouchesNetwork() {
        var http = mock(HttpClient.class);
        var client = new VnpayMerchantClient(config(false), http);
        assertThrows(IllegalStateException.class, () -> client.exchange(request(), new BigDecimal("50000")));
        verifyNoInteractions(http);
    }

    /** Mất ACK sau gửi không được retry và không lộ cause/message upstream có thể chứa credential. */
    @Test
    void networkFailureIsUncertainAndNotRetried() throws Exception {
        var http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenThrow(new IOException("upstream-secret-marker"));
        var error = assertThrows(VnpayMerchantClient.UncertainOutcomeException.class,
                () -> new VnpayMerchantClient(config(true), http).exchange(request(), new BigDecimal("50000")));
        assertNull(error.getCause());
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("upstream-secret-marker"));
        verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    /** HTTP 200 với response giả không được coi là hoàn tiền; checksum sai giữ kết quả chưa rõ. */
    @Test
    @SuppressWarnings("unchecked")
    void unsignedResponseIsUncertainWithoutRetry() throws Exception {
        var http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"vnp_ResponseCode\":\"00\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(response);
        assertThrows(VnpayMerchantClient.UncertainOutcomeException.class,
                () -> new VnpayMerchantClient(config(true), http).exchange(request(), new BigDecimal("50000")));
        verify(http, times(1)).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
    }

    /** Protocol tạo payload fixture; test chỉ kiểm tra transport, không tái định nghĩa thuật toán ký. */
    private Map<String, String> request() {
        return new VnpayMerchantProtocol("TESTONLY", "fixture-only").refund("req1", "pay1", new BigDecimal("50000"),
                "12345", Instant.parse("2026-11-13T00:00:00Z"), Instant.parse("2026-11-13T01:00:00Z"),
                "admin", "127.0.0.1", "Refund test");
    }

    private VnpayProperties config(boolean enabled) {
        var properties = new VnpayProperties();
        properties.setTmnCode("TESTONLY");
        properties.setHashSecret("fixture-only");
        properties.setMerchantApiEnabled(enabled);
        return properties;
    }
}
