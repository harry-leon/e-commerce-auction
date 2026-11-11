package com.nexus.reputation.entity;

import com.nexus.reputation.enums.PenaltyRefType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Thực thể ánh xạ bảng penalties trong DB reputation_svc (Flyway V2).
 * Khóa duy nhất (user_id, ref_type, ref_id) đảm bảo mỗi vi phạm chỉ bị trừ điểm một lần.
 */
@Entity
@Table(name = "penalties", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_penalty_ref", columnNames = {"user_id", "ref_type", "ref_id"})
})
public class Penalty {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "score_delta", nullable = false)
    private int scoreDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "ref_type", nullable = false, length = 30)
    private PenaltyRefType refType;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(name = "created_at", nullable = false)
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    private Instant createdAt;

    public Penalty() {
    }

    public Penalty(
            UUID id,
            UUID userId,
            String reason,
            int scoreDelta,
            PenaltyRefType refType,
            UUID refId,
            Instant createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.userId = userId;
        this.reason = reason;
        this.scoreDelta = scoreDelta;
        this.refType = refType;
        this.refId = refId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getScoreDelta() {
        return scoreDelta;
    }

    public void setScoreDelta(int scoreDelta) {
        this.scoreDelta = scoreDelta;
    }

    public PenaltyRefType getRefType() {
        return refType;
    }

    public void setRefType(PenaltyRefType refType) {
        this.refType = refType;
    }

    public UUID getRefId() {
        return refId;
    }

    public void setRefId(UUID refId) {
        this.refId = refId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
