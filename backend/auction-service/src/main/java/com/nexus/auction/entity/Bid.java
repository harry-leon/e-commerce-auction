package com.nexus.auction.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity ánh xạ bảng bids trong cơ sở dữ liệu auction_svc.
 *
 * Chức năng:
 * - Ghi nhận lịch sử từng lượt đặt giá của người mua cho một phiên đấu giá.
 * - Hỗ trợ idempotencyKey để ngăn chặn gửi trùng request từ phía client.
 *
 * Quy tắc và lý do:
 * - Không sử dụng Lombok; tự quản lý getter/setter.
 * - Khớp bảng vật lý bids (id, auction_id, bidder_id, amount, idempotency_key, placed_at).
 */
@Entity
@Table(name = "bids")
public class Bid {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "auction_id", nullable = false)
    private UUID auctionId;

    @Column(name = "bidder_id", nullable = false)
    private UUID bidderId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "idempotency_key", length = 100, unique = true)
    private String idempotencyKey;

    @Column(name = "placed_at", nullable = false)
    private Instant placedAt = Instant.now();

    public Bid() {
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

    public UUID getBidderId() {
        return bidderId;
    }

    public void setBidderId(UUID bidderId) {
        this.bidderId = bidderId;
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

    public Instant getPlacedAt() {
        return placedAt;
    }

    public void setPlacedAt(Instant placedAt) {
        this.placedAt = placedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Bid bid = (Bid) o;
        return Objects.equals(id, bid.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}

