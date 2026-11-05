package com.nexus.auction.dto.response;

import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO phản hồi thông tin phiên đấu giá cho Client/Frontend.
 *
 * Chức năng:
 * - Chuẩn hóa dữ liệu trả về cho các endpoint tạo, xem danh sách, xem chi tiết, cập nhật và hủy phiên.
 *
 * Quy tắc và lý do:
 * - Tương thích với interface frontend (RawApiAuction) và schema vật lý V2/V3.
 * - Tự quản lý getter/setter, không dùng Lombok.
 */
public class AuctionResponse {

    private UUID id;
    private UUID productId;
    private UUID skuId;
    private UUID sellerId;
    private BigDecimal startPrice;
    private BigDecimal bidIncrement;
    private BigDecimal currentPrice;
    private UUID highestBidderId;
    private AuctionVisibility visibility;
    private AuctionStatus status;
    private Instant startTime;
    private Instant endTime;
    private Integer extensionCount;
    private Integer version;
    private Instant createdAt;

    public AuctionResponse() {
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getProductId() {
        return productId;
    }

    public void setProductId(UUID productId) {
        this.productId = productId;
    }

    public UUID getSkuId() {
        return skuId;
    }

    public void setSkuId(UUID skuId) {
        this.skuId = skuId;
    }

    public UUID getSellerId() {
        return sellerId;
    }

    public void setSellerId(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public BigDecimal getStartPrice() {
        return startPrice;
    }

    public void setStartPrice(BigDecimal startPrice) {
        this.startPrice = startPrice;
    }

    public BigDecimal getBidIncrement() {
        return bidIncrement;
    }

    public void setBidIncrement(BigDecimal bidIncrement) {
        this.bidIncrement = bidIncrement;
    }

    public BigDecimal getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(BigDecimal currentPrice) {
        this.currentPrice = currentPrice;
    }

    public UUID getHighestBidderId() {
        return highestBidderId;
    }

    public void setHighestBidderId(UUID highestBidderId) {
        this.highestBidderId = highestBidderId;
    }

    public AuctionVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(AuctionVisibility visibility) {
        this.visibility = visibility;
    }

    public AuctionStatus getStatus() {
        return status;
    }

    public void setStatus(AuctionStatus status) {
        this.status = status;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public Integer getExtensionCount() {
        return extensionCount;
    }

    public void setExtensionCount(Integer extensionCount) {
        this.extensionCount = extensionCount;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

