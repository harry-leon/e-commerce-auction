package com.nexus.fulfillment.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Lớp cấu hình thuộc tính nghiệp vụ dành riêng cho fulfillment-service.
 *
 * Chức năng:
 * - Ánh xạ type-safe các tham số cấu hình từ prefix 'app.fulfillment' nạp từ Spring Cloud Config Server.
 * - Hỗ trợ cập nhật tham số tại runtime thông qua @RefreshScope và actuator refresh endpoint.
 *
 * Quy tắc và lý do:
 * - Quản lý tập trung các thông số giao vận (ngày giao ước tính, số lần thử lại, thời gian auto-confirm).
 * - Sử dụng getter/setter thuần theo chuẩn thiết kế không Lombok của fulfillment-service.
 * - Có sẵn giá trị mặc định để ứng dụng và các unit test vẫn chạy độc lập an toàn khi Config Server chưa bật.
 * - Dùng Bean Validation (@Validated, @NotNull, @Min) ngăn chặn cấu hình sai giá trị âm hoặc không hợp lệ.
 */
@Component
@ConfigurationProperties(prefix = "app.fulfillment")
@RefreshScope
@Validated
public class FulfillmentProperties {

    /**
     * Mã kho mặc định sử dụng khi yêu cầu giữ hàng không chỉ định warehouse_id (SRS §2.6: DEFAULT_WAREHOUSE_ID = WH_001).
     */
    @NotNull
    private String defaultWarehouseCode = "WH_001";

    /**
     * Thời gian hết hạn lệnh giữ hàng tồn kho tính theo phút (SRS §2.6: INVENTORY_RESERVATION_TIMEOUT_MINUTES = 30).
     */
    @NotNull
    @Min(value = 1, message = "Thời gian giữ kho tối thiểu phải là 1 phút")
    private Integer reservationTimeoutMinutes = 30;

    /**
     * Quy tắc cho phép bán vượt tồn hay không (SRS §2.6: ALLOW_OVERSELL = false).
     */
    @NotNull
    private Boolean allowOversell = false;

    /**
     * Chu kỳ quét giải phóng phiếu giữ hàng hết hạn tự động (miligiây, mặc định 60s).
     */
    @NotNull
    @Min(value = 1000, message = "Chu kỳ quét tối thiểu 1000ms")
    private Long reservationSweepIntervalMs = 60000L;

    @Valid
    @NotNull
    private Shipping shipping = new Shipping();

    public String getDefaultWarehouseCode() {
        return defaultWarehouseCode;
    }

    public void setDefaultWarehouseCode(String defaultWarehouseCode) {
        this.defaultWarehouseCode = defaultWarehouseCode;
    }

    public Integer getReservationTimeoutMinutes() {
        return reservationTimeoutMinutes;
    }

    public void setReservationTimeoutMinutes(Integer reservationTimeoutMinutes) {
        this.reservationTimeoutMinutes = reservationTimeoutMinutes;
    }

    public Boolean getAllowOversell() {
        return allowOversell;
    }

    public void setAllowOversell(Boolean allowOversell) {
        this.allowOversell = allowOversell;
    }

    public Long getReservationSweepIntervalMs() {
        return reservationSweepIntervalMs;
    }

    public void setReservationSweepIntervalMs(Long reservationSweepIntervalMs) {
        this.reservationSweepIntervalMs = reservationSweepIntervalMs;
    }

    public Shipping getShipping() {
        return shipping;
    }

    public void setShipping(Shipping shipping) {
        this.shipping = shipping;
    }

    public static class Shipping {
        @NotNull
        @Min(value = 1, message = "Số ngày giao hàng ước tính tối thiểu phải là 1")
        private Integer defaultDeliveryDays = 3;

        @NotNull
        @Min(value = 1, message = "Số lần giao lại tối thiểu phải là 1")
        private Integer maxRetryAttempts = 3;

        @NotNull
        @Min(value = 1, message = "Thời gian tự động xác nhận tối thiểu phải là 1 ngày")
        private Integer autoConfirmDays = 7;

        public Integer getDefaultDeliveryDays() {
            return defaultDeliveryDays;
        }

        public void setDefaultDeliveryDays(Integer defaultDeliveryDays) {
            this.defaultDeliveryDays = defaultDeliveryDays;
        }

        public Integer getMaxRetryAttempts() {
            return maxRetryAttempts;
        }

        public void setMaxRetryAttempts(Integer maxRetryAttempts) {
            this.maxRetryAttempts = maxRetryAttempts;
        }

        public Integer getAutoConfirmDays() {
            return autoConfirmDays;
        }

        public void setAutoConfirmDays(Integer autoConfirmDays) {
            this.autoConfirmDays = autoConfirmDays;
        }
    }
}
