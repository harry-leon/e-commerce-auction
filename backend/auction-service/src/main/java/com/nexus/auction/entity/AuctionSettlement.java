package com.nexus.auction.entity;

import com.nexus.auction.enums.SettlementResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity ánh xạ bảng auction_settlements trong cơ sở dữ liệu auction_svc.
 *
 * Chức năng:
 * - Lưu kết quả quyết toán chính thức khi một phiên đấu giá kết thúc.
 * - Ghi nhận người chiến thắng (winnerId), giá trúng (finalPrice), kết quả (SUCCESS/FAILED/CANCELLED/DEFAULTED)
 *   và hạn chót thanh toán (paymentDeadline = settledAt + 24h).
 *
 * Quy tắc và lý do:
 * - Có ràng buộc UNIQUE(auction_id) đảm bảo mỗi phiên đấu giá chỉ có duy nhất 1 bản ghi settlement.
 * - Không sử dụng Lombok theo thiết kế chung của auction-service; tự quản lý getter/setter.
 */
@Entity
@Table(name = "auction_settlements")
public class AuctionSettlement {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "auction_id", nullable = false, unique = true)
    private UUID auctionId;

    @Column(name = "winner_id")
    private UUID winnerId;

    @Column(name = "final_price", precision = 15, scale = 2)
    private BigDecimal finalPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 20)
    private SettlementResult result;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "payment_deadline")
    private Instant paymentDeadline;

    @Column(name = "settled_at", nullable = false)
    private Instant settledAt = Instant.now();

    public AuctionSettlement() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        AuctionSettlement that = (AuctionSettlement) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

