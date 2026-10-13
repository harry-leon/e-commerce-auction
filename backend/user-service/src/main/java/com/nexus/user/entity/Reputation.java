package com.nexus.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity đại diện cho điểm uy tín (Reputation) của người dùng theo SRS mục 3.1.2.
 *
 * @author Nguyễn Tiến Thương
 * @since 06/10/2026
 */
@Entity
@Table(name = "reputations")
public class Reputation {

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "score", nullable = false)
    private int score = 100;

    @Column(name = "trust_level", nullable = false, length = 20)
    private String trustLevel = "STANDARD";

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

    /**
     * Khởi tạo đối tượng Reputation mặc định.
     */
    public Reputation() {
    }

    /**
     * Khởi tạo đối tượng Reputation với mã người dùng.
     *
     * @param userId Mã định danh duy nhất của người dùng
     */
    public Reputation(UUID userId) {
        this.userId = userId;
        this.score = 100;
        this.trustLevel = "STANDARD";
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

    public String getTrustLevel() {
        return trustLevel;
    }

    public void setTrustLevel(String trustLevel) {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Reputation that = (Reputation) o;
        return Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
