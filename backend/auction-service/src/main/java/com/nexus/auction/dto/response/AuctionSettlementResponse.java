package com.nexus.auction.dto.response;

import com.nexus.auction.enums.SettlementResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO trả về kết quả quyết toán phiên đấu giá cho client hoặc service gọi nội bộ.
 *
 * Chức năng:
 * - Đóng gói dữ liệu kết quả quyết toán bao gồm thông tin người thắng, giá cuối, trạng thái và thời hạn thanh toán.
 */
public class AuctionSettlementResponse {

    private UUID id;
    private UUID auctionId;
    private UUID winnerId;
    private BigDecimal finalPrice;
    private SettlementResult result;
    private UUID orderId;
    private Instant paymentDeadline;
    private Instant settledAt;

    public AuctionSettlementResponse() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getAuctionId() {
        return auctionId;
    }

    public void setAuctionId(UUID auctionId) {
        this.auctionId = auctionId;
    }

    public UUID getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(UUID winnerId) {
        this.winnerId = winnerId;
    }

    public BigDecimal getFinalPrice() {
        return finalPrice;
    }

    public void setFinalPrice(BigDecimal finalPrice) {
        this.finalPrice = finalPrice;
    }

    public SettlementResult getResult() {
        return result;
    }

    public void setResult(SettlementResult result) {
        this.result = result;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public Instant getPaymentDeadline() {
        return paymentDeadline;
    }

    public void setPaymentDeadline(Instant paymentDeadline) {
        this.paymentDeadline = paymentDeadline;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public void setSettledAt(Instant settledAt) {
        this.settledAt = settledAt;
    }
}

