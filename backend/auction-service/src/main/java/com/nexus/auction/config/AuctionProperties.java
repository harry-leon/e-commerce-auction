package com.nexus.auction.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Lớp cấu hình thuộc tính nghiệp vụ dành riêng cho auction-service.
 *
 * Chức năng:
 * - Ánh xạ type-safe các tham số cấu hình từ prefix 'app.auction' nạp từ Spring Cloud Config Server.
 * - Hỗ trợ cập nhật tham số tại runtime thông qua @RefreshScope và actuator refresh endpoint.
 *
 * Quy tắc và lý do:
 * - Quản lý tập trung bước giá đấu giá, cơ chế chống sniping, giới hạn số phiên mở và khung thời lượng phiên.
 * - Tự quản lý getter/setter thuần theo chuẩn thiết kế không Lombok của auction-service.
 * - Có sẵn giá trị mặc định để ứng dụng và các unit test vẫn chạy độc lập an toàn khi Config Server chưa bật.
 * - Dùng Bean Validation (@Validated, @NotNull, @Min) ngăn chặn cấu hình sai giá trị âm hoặc không hợp lệ.
 */
@Component
@ConfigurationProperties(prefix = "app.auction")
@RefreshScope
@Validated
public class AuctionProperties {

    @Valid
    @NotNull
    private Bidding bidding = new Bidding();

    @Valid
    @NotNull
    private Rules rules = new Rules();

    @Valid
    @NotNull
    private Scheduler scheduler = new Scheduler();

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Bidding getBidding() {
        return bidding;
    }

    public void setBidding(Bidding bidding) {
        this.bidding = bidding;
    }

    public Rules getRules() {
        return rules;
    }

    public void setRules(Rules rules) {
        this.rules = rules;
    }

    public static class Bidding {
        @NotNull
        @Min(value = 1, message = "Bước giá tối thiểu phải lớn hơn hoặc bằng 1")
        private Long minIncrement = 1000L;

        @NotNull
        @Min(value = 0, message = "Thời gian chống sniping không được âm")
        private Integer antiSnipingSeconds = 120;

        @NotNull
        @Min(value = 1, message = "Số lần gia hạn chống sniping tối đa phải từ 1 trở lên")
        private Integer maxExtensionCount = 10;

        public Long getMinIncrement() {
            return minIncrement;
        }

        public void setMinIncrement(Long minIncrement) {
            this.minIncrement = minIncrement;
        }

        public Integer getAntiSnipingSeconds() {
            return antiSnipingSeconds;
        }

        public void setAntiSnipingSeconds(Integer antiSnipingSeconds) {
            this.antiSnipingSeconds = antiSnipingSeconds;
        }

        public Integer getMaxExtensionCount() {
            return maxExtensionCount;
        }

        public void setMaxExtensionCount(Integer maxExtensionCount) {
            this.maxExtensionCount = maxExtensionCount;
        }
    }

    public static class Rules {
        @NotNull
        @Min(value = 1, message = "Số phiên đấu giá tối đa phải lớn hơn hoặc bằng 1")
        private Integer maxActiveAuctionsPerSeller = 20;

        @NotNull
        @Min(value = 60, message = "Thời lượng tối thiểu của phiên đấu giá phải từ 60 giây trở lên")
        private Long minDurationSeconds = 3600L; // Mặc định 1 giờ

        @NotNull
        @Min(value = 60, message = "Thời lượng tối đa của phiên đấu giá phải từ 60 giây trở lên")
        private Long maxDurationSeconds = 604800L; // Mặc định 7 ngày = 7 * 24 * 3600

        public Integer getMaxActiveAuctionsPerSeller() {
            return maxActiveAuctionsPerSeller;
        }

        public void setMaxActiveAuctionsPerSeller(Integer maxActiveAuctionsPerSeller) {
            this.maxActiveAuctionsPerSeller = maxActiveAuctionsPerSeller;
        }

        public Long getMinDurationSeconds() {
            return minDurationSeconds;
        }

        public void setMinDurationSeconds(Long minDurationSeconds) {
            this.minDurationSeconds = minDurationSeconds;
        }

        public Long getMaxDurationSeconds() {
            return maxDurationSeconds;
        }

        public void setMaxDurationSeconds(Long maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
        }
    }

    public static class Scheduler {
        private boolean enabled = true;

        @NotNull
        @Min(value = 500, message = "Khoảng thời gian quét mở phiên phải từ 500ms trở lên")
        private Long openingIntervalMs = 5000L;

        @NotNull
        @Min(value = 500, message = "Khoảng thời gian quét đóng phiên phải từ 500ms trở lên")
        private Long closingIntervalMs = 10000L;

        @NotNull
        @Min(value = 500, message = "Khoảng thời gian quét quyết toán phải từ 500ms trở lên")
        private Long settlementIntervalMs = 10000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getOpeningIntervalMs() {
            return openingIntervalMs;
        }

        public void setOpeningIntervalMs(Long openingIntervalMs) {
            this.openingIntervalMs = openingIntervalMs;
        }

        public Long getClosingIntervalMs() {
            return closingIntervalMs;
        }

        public void setClosingIntervalMs(Long closingIntervalMs) {
            this.closingIntervalMs = closingIntervalMs;
        }

        public Long getSettlementIntervalMs() {
            return settlementIntervalMs;
        }

        public void setSettlementIntervalMs(Long settlementIntervalMs) {
            this.settlementIntervalMs = settlementIntervalMs;
        }
    }
}
