package com.nexus.fulfillment.shipping.adapter.resilience;

import com.nexus.common.exception.ApiException;
import com.nexus.common.exception.ErrorCode;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Chính sách thử lại (Retry Policy) chuyên biệt cho các cuộc gọi API hãng vận chuyển (SRS §2.6, §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Tự động thử lại cuộc gọi khi gặp các lỗi tạm thời (Transient Failures): timeout mạng, rớt kết nối hoặc lỗi 5xx từ carrier.
 * - Tuân thủ cấu hình giới hạn số lần thử lại (SHIPPING_RETRY_LIMIT = 3).
 *
 * Quy tắc và lý do:
 * - CHỈ thử lại khi gặp ngoại lệ mang tính chất tạm thời: ResourceAccessException, SocketTimeoutException, HttpServerErrorException.
 * - KHÔNG thử lại các lỗi 4xx (HttpClientErrorException) như địa chỉ không hợp lệ, sai chữ ký token, sai định dạng đơn,
 *   bởi vì gửi lại nhiều lần cùng một payload sai sẽ vô nghĩa và gây lãng phí tài nguyên.
 * - Áp dụng khoảng giãn cách nhẹ (Backoff Delay) giữa các lần thử lại để giảm xung đột tải lên hệ thống carrier.
 */
public class CarrierRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(CarrierRetryPolicy.class);

    private final String carrierCode;
    private final int maxAttempts;
    private final long backoffDelayMs;

    public CarrierRetryPolicy(String carrierCode, int maxAttempts) {
        this(carrierCode, maxAttempts, 150L);
    }

    public CarrierRetryPolicy(String carrierCode, int maxAttempts, long backoffDelayMs) {
        this.carrierCode = carrierCode != null ? carrierCode.toUpperCase() : "UNKNOWN";
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffDelayMs = Math.max(0, backoffDelayMs);
    }

    /**
     * Thực thi supplier với cơ chế tự động thử lại tối đa maxAttempts lần.
     */
    public <T> T execute(Supplier<T> supplier) {
        int attempt = 0;
        Throwable lastException = null;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                return supplier.get();
            } catch (Throwable ex) {
                lastException = ex;

                if (!isRetryable(ex) || attempt >= maxAttempts) {
                    if (attempt > 1) {
                        log.warn("Gọi API hãng {} thất bại sau {}/{} lần thử lại: {}",
                                carrierCode, attempt, maxAttempts, ex.getMessage());
                    }
                    if (ex instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    throw new RuntimeException(ex);
                }

                log.info("Lần gọi API hãng {} thứ {} thất bại do lỗi tạm thời [{}], chuẩn bị thử lại lần {}",
                        carrierCode, attempt, ex.getMessage(), attempt + 1);

                if (backoffDelayMs > 0) {
                    try {
                        Thread.sleep(backoffDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Tiến trình thử lại bị gián đoạn", ie);
                    }
                }
            }
        }

        if (lastException instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new RuntimeException("Gọi API thất bại sau " + maxAttempts + " lần thử lại", lastException);
    }

    /**
     * Xác định xem ngoại lệ có thuộc loại tạm thời được phép thử lại hay không.
     */
    public boolean isRetryable(Throwable ex) {
        if (ex == null) {
            return false;
        }
        // Lỗi 4xx từ phía client -> không retry
        if (ex instanceof HttpClientErrorException) {
            return false;
        }
        // Lỗi nghiệp vụ ApiException không phải timeout/carrier_error -> không retry
        if (ex instanceof ApiException apiEx) {
            String code = apiEx.getCode();
            return ErrorCode.SHIPMENT_CARRIER_TIMEOUT.name().equals(code)
                    || ErrorCode.SHIPMENT_CARRIER_ERROR.name().equals(code);
        }
        // Timeout hoặc mất kết nối mạng
        if (ex instanceof ResourceAccessException || ex instanceof SocketTimeoutException || ex instanceof IOException) {
            return true;
        }
        // Lỗi máy chủ 5xx từ carrier
        if (ex instanceof HttpServerErrorException) {
            return true;
        }
        // Kiểm tra nguyên nhân sâu hơn (cause)
        if (ex.getCause() != null && ex.getCause() != ex) {
            return isRetryable(ex.getCause());
        }
        return false;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
