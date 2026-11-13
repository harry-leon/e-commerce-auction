package com.nexus.fulfillment.shipping.adapter.resilience;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bộ ngắt mạch Circuit Breaker chuyên biệt cho từng hãng vận chuyển (SRS §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Bảo vệ hệ thống khỏi sự cố sập nguồn/treo mạng dây chuyền (Cascading Failure) khi API của hãng vận chuyển gặp sự cố.
 * - Cô lập lỗi riêng theo từng hãng (Per-carrier Circuit Breaker): lỗi của GHN không làm ảnh hưởng tới GHTK và ngược lại.
 * - Triển khai máy trạng thái CLOSED -> OPEN -> HALF_OPEN -> CLOSED theo chuẩn Resilience4j:
 *   + Khi số lần lỗi liên tiếp vượt ngưỡng (consecutiveFailures >= failureThreshold), chuyển sang OPEN.
 *   + Khi ở trạng thái OPEN, mọi lời gọi bị từ chối ngay lập tức (Fail-Fast) mà không chờ timeout mạng, không nghẽn thread pool.
 *   + Sau khoảng thời gian chờ (cooldownDurationMs), tự động chuyển sang HALF_OPEN để gửi yêu cầu thăm dò.
 *   + Nếu yêu cầu thăm dò thành công, mạch tự động phục hồi về CLOSED.
 *
 * Quy tắc và lý do:
 * - Sử dụng các biến nguyên tử (AtomicReference, AtomicInteger, AtomicLong) để đảm bảo thread-safe trong môi trường xử lý đồng thời cao.
 * - Ném mã lỗi SHIPMENT_CARRIER_ERROR kèm thông điệp rõ ràng giúp tầng điều phối kích hoạt fallback chuyển hãng hoặc lưu hàng chờ.
 */
public class CarrierCircuitBreaker {

    private final String carrierCode;
    private final int failureThreshold;
    private final long cooldownDurationMs;
    private final int permittedHalfOpenProbes;

    private final AtomicReference<CarrierCircuitBreakerState> state = new AtomicReference<>(CarrierCircuitBreakerState.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger successfulCalls = new AtomicInteger(0);
    private final AtomicLong lastStateChangedTimeMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger halfOpenProbeCount = new AtomicInteger(0);

    public CarrierCircuitBreaker(String carrierCode) {
        this(carrierCode, 3, 10000L, 2);
    }

    /**
     * Khởi tạo Circuit Breaker với các tham số ngưỡng lỗi và cooldown tùy chỉnh.
     * Quy tắc: cooldownDurationMs tối thiểu là 1ms (thay vì cố định 1000ms) để hỗ trợ cấu hình linh hoạt và kiểm thử nhanh.
     */
    public CarrierCircuitBreaker(String carrierCode, int failureThreshold, long cooldownDurationMs, int permittedHalfOpenProbes) {
        this.carrierCode = carrierCode != null ? carrierCode.toUpperCase() : "UNKNOWN";
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldownDurationMs = Math.max(1L, cooldownDurationMs);
        this.permittedHalfOpenProbes = Math.max(1, permittedHalfOpenProbes);
    }

    /**
     * Kiểm tra quyền thực thi lệnh gọi trước khi kết nối tới carrier.
     * Ném ngoại lệ fail-fast nếu mạch đang ở trạng thái OPEN và chưa hết thời gian cooldown.
     */
    public void acquirePermission() {
        CarrierCircuitBreakerState currentState = state.get();
        long now = System.currentTimeMillis();

        if (currentState == CarrierCircuitBreakerState.OPEN) {
            long openDuration = now - lastStateChangedTimeMs.get();
            if (openDuration >= cooldownDurationMs) {
                // Hết thời gian chờ, chuyển sang thăm dò HALF_OPEN
                if (state.compareAndSet(CarrierCircuitBreakerState.OPEN, CarrierCircuitBreakerState.HALF_OPEN)) {
                    lastStateChangedTimeMs.set(now);
                    halfOpenProbeCount.set(0);
                }
            } else {
                throw new ApiException(
                        ErrorCode.SHIPMENT_CARRIER_ERROR,
                        "Hãng vận chuyển " + carrierCode + " đang tạm ngưng phục vụ (Circuit Breaker OPEN). "
                                + "Kích hoạt cơ chế fail-fast để bảo vệ đơn hàng, thử lại sau "
                                + ((cooldownDurationMs - openDuration) / 1000) + "s"
                );
            }
        }

        if (state.get() == CarrierCircuitBreakerState.HALF_OPEN) {
            int probes = halfOpenProbeCount.incrementAndGet();
            if (probes > permittedHalfOpenProbes) {
                throw new ApiException(
                        ErrorCode.SHIPMENT_CARRIER_ERROR,
                        "Hãng vận chuyển " + carrierCode + " đang trong quá trình thăm dò hồi phục (HALF_OPEN). "
                                + "Số lượt thăm dò đồng thời đã đạt giới hạn."
                );
            }
        }

        totalCalls.incrementAndGet();
    }

    /**
     * Ghi nhận lệnh gọi thành công: reset bộ đếm lỗi và đóng mạch nếu đang ở HALF_OPEN.
     */
    public void recordSuccess() {
        successfulCalls.incrementAndGet();
        consecutiveFailures.set(0);

        if (state.get() == CarrierCircuitBreakerState.HALF_OPEN) {
            if (state.compareAndSet(CarrierCircuitBreakerState.HALF_OPEN, CarrierCircuitBreakerState.CLOSED)) {
                lastStateChangedTimeMs.set(System.currentTimeMillis());
                halfOpenProbeCount.set(0);
            }
        }
    }

    /**
     * Ghi nhận lệnh gọi thất bại: tăng bộ đếm lỗi, nếu vượt ngưỡng sẽ ngắt mạch (chuyển sang OPEN).
     */
    public void recordFailure(Throwable throwable) {
        int failures = consecutiveFailures.incrementAndGet();
        CarrierCircuitBreakerState currentState = state.get();

        if (currentState == CarrierCircuitBreakerState.HALF_OPEN) {
            // Trong lúc thăm dò mà vẫn lỗi thì lập tức mở lại mạch
            if (state.compareAndSet(CarrierCircuitBreakerState.HALF_OPEN, CarrierCircuitBreakerState.OPEN)) {
                lastStateChangedTimeMs.set(System.currentTimeMillis());
                halfOpenProbeCount.set(0);
            }
            return;
        }

        if (currentState == CarrierCircuitBreakerState.CLOSED && failures >= failureThreshold) {
            if (state.compareAndSet(CarrierCircuitBreakerState.CLOSED, CarrierCircuitBreakerState.OPEN)) {
                lastStateChangedTimeMs.set(System.currentTimeMillis());
            }
        }
    }

    /** Thiết lập thủ công trạng thái mạch phục vụ kiểm thử hoặc can thiệp quản trị. */
    public void transitionToState(CarrierCircuitBreakerState targetState) {
        state.set(targetState);
        lastStateChangedTimeMs.set(System.currentTimeMillis());
        if (targetState == CarrierCircuitBreakerState.CLOSED) {
            consecutiveFailures.set(0);
            halfOpenProbeCount.set(0);
        }
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public CarrierCircuitBreakerState getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int getTotalCalls() {
        return totalCalls.get();
    }

    public int getSuccessfulCalls() {
        return successfulCalls.get();
    }

    public Instant getLastStateChangedAt() {
        return Instant.ofEpochMilli(lastStateChangedTimeMs.get());
    }
}
