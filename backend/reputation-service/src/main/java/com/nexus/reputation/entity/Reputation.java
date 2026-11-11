package com.nexus.reputation.entity;

import com.nexus.reputation.enums.TrustLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Thực thể ánh xạ bảng reputations trong DB reputation_svc (khớp Flyway V2 + V3).
 * Lưu tổng hợp điểm uy tín hiện tại, phân hạng và bộ đếm giao dịch/đấu giá của người dùng.
 */
@Entity
@Table(name = "reputations")
public class Reputation {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "score", nullable = false)
    private int score = 100;

    @Enumerated(EnumType.STRING)
    @Column(name = "trust_level", nullable = false, length = 20)
    private TrustLevel trustLevel = TrustLevel.STANDARD;

    @Column(name = "total_tx", nullable = false)
    private int totalTx = 0;

    @Column(name = "success_tx", nullable = false)
    private int successTx = 0;

    @Column(name = "failed_tx", nullable = false)
    private int failedTx = 0;

    @Column(name = "auction_won", nullable = false)
    private int auctionWon = 0;

    @Column(name = "auction_failed", nullable = false)
    private int auctionFailed = 0;

    public Reputation() {
    }

    public Reputation(UUID userId, int score, TrustLevel trustLevel) {
        this.userId = userId;
        this.score = score;
        this.trustLevel = trustLevel != null ? trustLevel : TrustLevel.STANDARD;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public TrustLevel getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(TrustLevel trustLevel) {
        this.trustLevel = trustLevel;
    }

    public int getTotalTx() {
        return totalTx;
    }

    public void setTotalTx(int totalTx) {
        this.totalTx = totalTx;
    }

    public int getSuccessTx() {
        return successTx;
    }

    public void setSuccessTx(int successTx) {
        this.successTx = successTx;
    }

    public int getFailedTx() {
        return failedTx;
    }

    public void setFailedTx(int failedTx) {
        this.failedTx = failedTx;
    }

    public int getAuctionWon() {
        return auctionWon;
    }

    public void setAuctionWon(int auctionWon) {
        this.auctionWon = auctionWon;
    }

    public int getAuctionFailed() {
        return auctionFailed;
    }

    public void setAuctionFailed(int auctionFailed) {
        this.auctionFailed = auctionFailed;
    }
}
