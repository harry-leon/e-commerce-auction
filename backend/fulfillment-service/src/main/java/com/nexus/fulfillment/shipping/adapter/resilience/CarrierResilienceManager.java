package com.nexus.fulfillment.shipping.adapter.resilience;

import com.nexus.fulfillment.config.FulfillmentProperties;
import com.nexus.fulfillment.dto.response.CarrierResilienceStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Trình quản lý khả năng chịu lỗi và ngắt mạch đa hãng vận chuyển (Carrier Resilience Manager - KH-S3-2).
 *
 * Chức năng:
 * - Khởi tạo và quản trị vòng đời của từng CarrierCircuitBreaker và CarrierRetryPolicy độc lập cho mỗi carrier (GHN, GHTK).
 * - Tích hợp các tham số cấu hình hệ thống:
 *   + SHIPPING_API_TIMEOUT_MS = 3000
 *   + SHIPPING_RETRY_LIMIT = 3
 * - Cung cấp hàm bọc thực thi an toàn executeWithResilience() kết hợp cả Circuit Breaker và Retry Policy:
 *   1. Kiểm tra circuit breaker: nếu OPEN -> fail-fast ngay lập tức hoặc kích hoạt fallback.
 *   2. Chỉ quote/trackShipment được retry; mutation hoặc thao tác chưa biết gọi đúng một lần mỗi invocation.
 *   3. Ghi nhận thành công/thất bại vào Circuit Breaker để cập nhật trạng thái mạch.
 *
 * Quy tắc và lý do:
 * - Cô lập sự cố giữa các hãng: lỗi của một hãng thứ ba không bao giờ làm nghẽn thread pool hoặc chặn luồng đặt hàng chính.
 * - Cho phép tra cứu trạng thái và can thiệp reset mạch qua API quản trị phục vụ giám sát vận hành.
 */
@Component
public class CarrierResilienceManager {

    private static final Logger log = LoggerFactory.getLogger(CarrierResilienceManager.class);

    private final FulfillmentProperties properties;
    private final Map<String, CarrierCircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final Map<String, CarrierRetryPolicy> retryPolicies = new ConcurrentHashMap<>();

    public CarrierResilienceManager(FulfillmentProperties properties) {
        this.properties = properties;
    }

    /**
     * Lấy hoặc tạo mới Circuit Breaker cho hãng vận chuyển chỉ định.
     */
    public CarrierCircuitBreaker getCircuitBreaker(String carrierCode) {
        String normalized = normalizeCode(carrierCode);
        return circuitBreakers.computeIfAbsent(normalized, code -> {
            int failureThreshold = 3;
            long cooldownMs = 10000L;
            int probes = 2;
            return new CarrierCircuitBreaker(code, failureThreshold, cooldownMs, probes);
        });
    }

    /**
     * Lấy hoặc tạo mới Retry Policy cho hãng vận chuyển chỉ định.
     */
    public CarrierRetryPolicy getRetryPolicy(String carrierCode) {
        String normalized = normalizeCode(carrierCode);
        return retryPolicies.computeIfAbsent(normalized, code -> {
            int retryLimit = (properties.getShipping() != null && properties.getShipping().getRetryLimit() != null)
                    ? properties.getShipping().getRetryLimit()
                    : 3;
            return new CarrierRetryPolicy(code, retryLimit);
        });
    }

    /**
     * Bọc Circuit Breaker cho mọi thao tác; chỉ retry/fallback các thao tác đọc đã biết.
     * Mutation timeout có thể đã tạo vận đơn phía carrier, nên trả lỗi để đối soát thay vì thử lại hoặc giả thành công.
     *
     * @param carrierCode mã hãng vận chuyển ("GHN", "GHTK")
     * @param operation tên thao tác ("quote", "createShipment", "trackShipment")
     * @param action hàm supplier thực hiện gọi API
     * @param fallback hàm dự phòng khi mạch OPEN hoặc retry thất bại (có thể là null để ném ngoại lệ)
     * @return kết quả thực thi
     */
    public <T> T executeWithResilience(
            String carrierCode,
            String operation,
            Supplier<T> action,
            Supplier<T> fallback
    ) {
        CarrierCircuitBreaker cb = getCircuitBreaker(carrierCode);
        boolean readOperation = "quote".equals(operation) || "trackShipment".equals(operation);
        CarrierRetryPolicy retryPolicy = getRetryPolicy(carrierCode);

        try {
            cb.acquirePermission();
        } catch (Throwable ex) {
            log.warn("Circuit Breaker từ chối thao tác {} trên hãng {}: {}", operation, carrierCode, ex.getMessage());
            if (readOperation && fallback != null) {
                return fallback.get();
            }
            throw ex;
        }

        try {
            // Key idempotency trong DB nội bộ không chứng minh carrier đã khử trùng khi response bị mất.
            T result = readOperation ? retryPolicy.execute(action) : action.get();
            cb.recordSuccess();
            return result;
        } catch (Throwable ex) {
            cb.recordFailure(ex);
            log.error("Thao tác {} trên hãng {} thất bại: {}", operation, carrierCode, ex.getMessage());
            if (readOperation && fallback != null) {
                log.info("Kích hoạt fallback cho thao tác {} trên hãng {}", operation, carrierCode);
                return fallback.get();
            }
            throw ex;
        }
    }

    /**
     * Lấy danh sách trạng thái ngắt mạch hiện tại của tất cả các hãng.
     */
    public List<CarrierResilienceStatus> getResilienceStatuses() {
        List<CarrierResilienceStatus> result = new ArrayList<>();
        List<String> carriers = (properties.getShipping() != null && properties.getShipping().getSupportedCarriers() != null)
                ? properties.getShipping().getSupportedCarriers()
                : List.of("GHN", "GHTK");

        for (String c : carriers) {
            CarrierCircuitBreaker cb = getCircuitBreaker(c);
            int retryLimit = (properties.getShipping() != null && properties.getShipping().getRetryLimit() != null)
                    ? properties.getShipping().getRetryLimit()
                    : 3;
            int timeoutMs = (properties.getShipping() != null && properties.getShipping().getApiTimeoutMs() != null)
                    ? properties.getShipping().getApiTimeoutMs()
                    : 3000;

            result.add(new CarrierResilienceStatus(
                    cb.getCarrierCode(),
                    cb.getState(),
                    cb.getConsecutiveFailures(),
                    cb.getTotalCalls(),
                    cb.getSuccessfulCalls(),
                    3,
                    retryLimit,
                    timeoutMs,
                    cb.getLastStateChangedAt()
            ));
        }
        return result;
    }

    private String normalizeCode(String code) {
        return (code != null && !code.isBlank()) ? code.trim().toUpperCase(Locale.ROOT) : "DEFAULT";
    }
}
