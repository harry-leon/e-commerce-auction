package com.nexus.reputation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Thực thể ánh xạ bảng ratings trong DB reputation_svc (Flyway V2).
 * Ràng buộc (order_id, rater_id) đảm bảo mỗi bên chỉ được đánh giá đối tác tối đa một lần cho mỗi đơn hàng.
 */
@Entity
@Table(name = "ratings", uniqueConstraints = {
        @UniqueConstraint(name = "uq_order_rater", columnNames = {"order_id", "rater_id"})
})
public class Rating {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "rater_id", nullable = false)
    private UUID raterId;

    @Column(name = "ratee_id", nullable = false)
    private UUID rateeId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "comment", length = 1000)
    private String comment;

    @Column(name = "is_aggregated", nullable = false)
    private boolean aggregated;

    @Column(name = "is_disputed", nullable = false)
    private boolean disputed;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    public Rating() {
    }

    public Rating(
            UUID id,
            UUID raterId,
            UUID rateeId,
            UUID orderId,
            int score,
            String comment,
            boolean aggregated,
            boolean disputed,
            Instant createdAt) {
        this.id = id;
        this.raterId = raterId;
        this.rateeId = rateeId;
        this.orderId = orderId;
        this.score = score;
        this.comment = comment;
        this.aggregated = aggregated;
        this.disputed = disputed;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getRaterId() {
        return raterId;
    }

    public void setRaterId(UUID raterId) {
        this.raterId = raterId;
    }

    public UUID getRateeId() {
        return rateeId;
    }

    public void setRateeId(UUID rateeId) {
        this.rateeId = rateeId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public boolean isAggregated() {
        return aggregated;
    }

    public void setAggregated(boolean aggregated) {
        this.aggregated = aggregated;
    }

    public boolean isDisputed() {
        return disputed;
    }

    public void setDisputed(boolean disputed) {
        this.disputed = disputed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
