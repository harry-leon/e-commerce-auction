package com.nexus.reputation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Tham số nghiệp vụ tính điểm uy tín, đánh giá, kiểm tra điều kiện đấu giá và xử phạt theo SRS §2.6 & §3.5.3.
 */
@Configuration
@ConfigurationProperties(prefix = "app.reputation")
@RefreshScope
@Validated
public class ReputationProperties {

    /** Điểm trừ mặc định khi người thắng đấu giá không thanh toán trước hạn 24h (SRS §2.6). */
    @Min(1)
    @Max(100)
    private int paymentTimeoutPenaltyScore = 10;

    /** Điểm khởi tạo cho tài khoản chưa có bản ghi reputations (khớp DEFAULT 100 ở V2). */
    @Min(0)
    private int initialScore = 100;

    /** Ngưỡng dưới mức này xếp hạng uy tín LOW. */
    @Min(1)
    private int lowThreshold = 60;

    /** Ngưỡng đạt mức uy tín HIGH khi tài khoản có lịch sử giao dịch tốt. */
    @Min(60)
    private int highThreshold = 110;

    /** Ngưỡng đạt mức uy tín VIP. */
    @Min(100)
    private int vipThreshold = 150;

    /** Điểm uy tín tối thiểu để tham gia đặt giá đấu giá (SRS §3.5.3). */
    @Min(0)
    private int minReputationToBid = 40;

    /** Điểm uy tín tối thiểu để mở phiên đấu giá mới (SRS §3.5.3). */
    @Min(0)
    private int minReputationToSell = 50;

    /** Cờ bật/tắt kiểm tra điều kiện uy tín trước khi tạo phiên hoặc đặt giá đấu giá. */
    private boolean reputationEnforcementEnabled = true;

    /** Bật/tắt xác thực đơn hàng qua commerce-service trước khi cho phép tạo đánh giá. */
    private boolean orderVerificationEnabled = true;

    /** URL gốc của commerce-service dùng để gọi GET /api/v1/orders/{orderId}. */
    private String commerceBaseUrl = "http://localhost:8083";

    public int getPaymentTimeoutPenaltyScore() {
        return paymentTimeoutPenaltyScore;
    }

    public void setPaymentTimeoutPenaltyScore(int paymentTimeoutPenaltyScore) {
        this.paymentTimeoutPenaltyScore = paymentTimeoutPenaltyScore;
    }

    public int getInitialScore() {
        return initialScore;
    }

    public void setInitialScore(int initialScore) {
        this.initialScore = initialScore;
    }

    public int getLowThreshold() {
        return lowThreshold;
    }

    public void setLowThreshold(int lowThreshold) {
        this.lowThreshold = lowThreshold;
    }

    public int getHighThreshold() {
        return highThreshold;
    }

    public void setHighThreshold(int highThreshold) {
        this.highThreshold = highThreshold;
    }

    public int getVipThreshold() {
        return vipThreshold;
    }

    public void setVipThreshold(int vipThreshold) {
        this.vipThreshold = vipThreshold;
    }

    public int getMinReputationToBid() {
        return minReputationToBid;
    }

    public void setMinReputationToBid(int minReputationToBid) {
        this.minReputationToBid = minReputationToBid;
    }

    public int getMinReputationToSell() {
        return minReputationToSell;
    }

    public void setMinReputationToSell(int minReputationToSell) {
        this.minReputationToSell = minReputationToSell;
    }

    public boolean isReputationEnforcementEnabled() {
        return reputationEnforcementEnabled;
    }

    public void setReputationEnforcementEnabled(boolean reputationEnforcementEnabled) {
        this.reputationEnforcementEnabled = reputationEnforcementEnabled;
    }

    public boolean isOrderVerificationEnabled() {
        return orderVerificationEnabled;
    }

    public void setOrderVerificationEnabled(boolean orderVerificationEnabled) {
        this.orderVerificationEnabled = orderVerificationEnabled;
    }

    public String getCommerceBaseUrl() {
        return commerceBaseUrl;
    }

    public void setCommerceBaseUrl(String commerceBaseUrl) {
        this.commerceBaseUrl = commerceBaseUrl;
    }
}
