package com.nexus.reputation.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Tham số nghiệp vụ tính điểm uy tín và xử phạt theo SRS §2.6 & §3.5.3.
 * Mặc định giữ chuẩn PAYMENT_TIMEOUT_PENALTY_SCORE = 10 để áp dụng khi winner quá hạn thanh toán 24 giờ.
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
}
