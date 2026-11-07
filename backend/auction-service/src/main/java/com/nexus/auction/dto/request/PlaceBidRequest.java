package com.nexus.auction.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * DTO yêu cầu đặt giá (Place Bid Request).
 *
 * Chức năng:
 * - Nhận mức giá thầu và idempotencyKey từ người mua (BUYER).
 * - Kiểm tra tính hợp lệ cơ bản tại tầng Controller (amount không null và lớn hơn 0).
 */
@Schema(description = "Yêu cầu đặt giá thầu cho phiên đấu giá")
public class PlaceBidRequest {

    @NotNull(message = "Số tiền đặt giá không được để trống")
    @DecimalMin(value = "0.01", message = "Số tiền đặt giá phải lớn hơn 0")
    @Schema(description = "Số tiền đặt giá (VND)", example = "5200000.00", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @Schema(description = "Khóa chống trùng lặp (Idempotency Key)", example = "uuid-bid-001")
    private String idempotencyKey;

    public PlaceBidRequest() {
    }

    public PlaceBidRequest(BigDecimal amount, String idempotencyKey) {
        this.amount = amount;
        this.idempotencyKey = idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}

