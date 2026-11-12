package com.nexus.fulfillment.shipping.adapter.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import com.nexus.fulfillment.config.FulfillmentProperties;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;

/**
 * Kiểm thử tính năng Chịu lỗi, Thử lại và Ngắt mạch (Fault Tolerance: Retry & Circuit Breaker) (SRS §3.6.2, KH-S3-2).
 *
 * Kiểm tra:
 * - Circuit Breaker chuyển CLOSED -> OPEN khi số lỗi liên tiếp vượt ngưỡng (failureThreshold = 3).
 * - Cơ chế Fail-Fast từ chối cuộc gọi ngay lập tức khi OPEN, không làm nghẽn luồng đơn hàng.
 * - Chuyển sang HALF_OPEN sau thời gian chờ cooldown và phục hồi CLOSED khi probe thành công.
 * - CarrierRetryPolicy tự động thử lại tối đa 3 lần với lỗi mạng/timeout và KHÔNG thử lại với lỗi 4xx.
 */
class CarrierResilienceTest {

    private FulfillmentProperties properties;
    private CarrierResilienceManager resilienceManager;

    @BeforeEach
    void setUp() {
        properties = new FulfillmentProperties();
        properties.getShipping().setRetryLimit(3);
        properties.getShipping().setApiTimeoutMs(3000);
        resilienceManager = new CarrierResilienceManager(properties);
    }

    @Test
    @DisplayName("CircuitBreaker: ngắt mạch (OPEN) sau 3 lần lỗi liên tiếp và kích hoạt Fail-Fast")
    void circuitBreaker_opensAfterConsecutiveFailures_andFailsFast() {
        CarrierCircuitBreaker cb = new CarrierCircuitBreaker("GHN", 3, 5000L, 2);

        // Ban đầu mạch CLOSED
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.CLOSED);

        // Lần 1 thất bại
        cb.acquirePermission();
        cb.recordFailure(new ResourceAccessException("Timeout 1"));
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.CLOSED);

        // Lần 2 thất bại
        cb.acquirePermission();
        cb.recordFailure(new ResourceAccessException("Timeout 2"));
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.CLOSED);

        // Lần 3 thất bại -> Chuyển sang OPEN
        cb.acquirePermission();
        cb.recordFailure(new ResourceAccessException("Timeout 3"));
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.OPEN);

        // Lần gọi tiếp theo bị từ chối ngay lập tức (Fail-Fast)
        assertThatThrownBy(cb::acquirePermission)
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo(ErrorCode.SHIPMENT_CARRIER_ERROR.name());
    }

    @Test
    @DisplayName("CircuitBreaker: hồi phục từ OPEN -> HALF_OPEN sau cooldown và về CLOSED khi probe thành công")
    void circuitBreaker_recoversToClosedAfterSuccessfulProbe() throws InterruptedException {
        // Cooldown siêu ngắn 100ms phục vụ test
        CarrierCircuitBreaker cb = new CarrierCircuitBreaker("GHTK", 2, 100L, 1);

        cb.acquirePermission();
        cb.recordFailure(new ResourceAccessException("Error 1"));
        cb.acquirePermission();
        cb.recordFailure(new ResourceAccessException("Error 2"));
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.OPEN);

        // Đợi hết 100ms cooldown
        Thread.sleep(120);

        // Lệnh gọi tiếp theo kích hoạt chuyển sang HALF_OPEN
        cb.acquirePermission();
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.HALF_OPEN);

        // Thăm dò thành công -> Mạch đóng lại CLOSED
        cb.recordSuccess();
        assertThat(cb.getState()).isEqualTo(CarrierCircuitBreakerState.CLOSED);
        assertThat(cb.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("RetryPolicy: thử lại tối đa 3 lần khi gặp lỗi mạng tạm thời và dừng khi thành công")
    void retryPolicy_retriesOnTransientErrorUntilSuccess() {
        CarrierRetryPolicy retryPolicy = new CarrierRetryPolicy("GHN", 3, 10L);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = retryPolicy.execute(() -> {
            int current = attempts.incrementAndGet();
            if (current < 3) {
                throw new ResourceAccessException("Socket timed out", new SocketTimeoutException("timeout"));
            }
            return "SUCCESS_ON_ATTEMPT_3";
        });

        assertThat(result).isEqualTo("SUCCESS_ON_ATTEMPT_3");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("RetryPolicy: KHÔNG thử lại với lỗi 4xx client error và ném lỗi ngay lần đầu")
    void retryPolicy_doesNotRetryOn4xxError() {
        CarrierRetryPolicy retryPolicy = new CarrierRetryPolicy("GHN", 3, 10L);
        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> retryPolicy.execute(() -> {
            attempts.incrementAndGet();
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Invalid address");
        })).isInstanceOf(HttpClientErrorException.class);

        // Chỉ chạy 1 lần duy nhất, không thử lại
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("CarrierResilienceManager: trả về fallback an toàn khi Circuit Breaker đang OPEN")
    void resilienceManager_invokesFallbackWhenCircuitOpen() {
        CarrierCircuitBreaker cb = resilienceManager.getCircuitBreaker("GHN");
        cb.transitionToState(CarrierCircuitBreakerState.OPEN);

        String result = resilienceManager.executeWithResilience(
                "GHN",
                "quote",
                () -> "REAL_CALL_NEVER_EXECUTED",
                () -> "FALLBACK_QUOTE_RESULT"
        );

        assertThat(result).isEqualTo("FALLBACK_QUOTE_RESULT");
    }
}
