package com.nexus.auction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO đại diện cho một bản ghi lượt đặt giá (Bid History Item).
 *
 * Chức năng:
 * - Trả về thông tin chi tiết một lượt đặt giá: mã bid, người đặt, số tiền, thời gian đặt giá.
 * - Hỗ trợ che danh tính bidder (masked username/id) để bảo mật thông tin người mua trên sàn.
 */
@Schema(description = "Thông tin chi tiết một lượt đặt giá")
public class BidResponse {

    @Schema(description = "Mã lượt đặt giá", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Mã phiên đấu giá", example = "c1f7b9c2-5717-4562-b3fc-2c963f66afa6")
    private UUID auctionId;

    @Schema(description = "Mã người đặt giá", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
    private UUID bidderId;

    @Schema(description = "Tên/mã người đặt giá đã được che danh tính (Masked)", example = "9b1d***cb6d")
    private String bidderMask;

    @Schema(description = "Số tiền đặt giá", example = "5200000.00")
    private BigDecimal amount;

    @Schema(description = "Khóa chống trùng lặp", example = "uuid-bid-001")
    private String idempotencyKey;

    @Schema(description = "Thời điểm đặt giá", example = "2026-11-07T10:15:30Z")
    private Instant placedAt;

    public BidResponse() {
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

    public String getBidderMask() {
        return bidderMask;
    }

    public void setBidderMask(String bidderMask) {
        this.bidderMask = bidderMask;
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
}

