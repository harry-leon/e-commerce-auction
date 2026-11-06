package com.nexus.auction.entity;

import com.nexus.auction.enums.AuctionStatus;
import com.nexus.auction.enums.AuctionVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Entity ánh xạ bảng auctions trong cơ sở dữ liệu auction_svc.
 *
 * Chức năng:
 * - Lưu trữ thông tin cốt lõi của phiên đấu giá: sản phẩm, người bán, giá khởi điểm, bước giá,
 *   thời gian bắt đầu/kết thúc, giá hiện tại và người đang trả giá cao nhất.
 * - Hỗ trợ Optimistic Locking qua trường version để chống race condition khi đặt giá hoặc đóng phiên.
 *
 * Quy tắc và lý do:
 * - Không sử dụng Lombok theo quy chuẩn thiết kế nội bộ của auction-service; tự quản lý getter/setter.
 * - Khớp 100% cột vật lý trong PostgreSQL theo Flyway V1/V2/V3 (không thêm trường dư thừa ngoài schema).
 */
@Entity
@Table(name = "auctions")
public class Auction {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "start_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal startPrice;

    @Column(name = "bid_increment", nullable = false, precision = 15, scale = 2)
    private BigDecimal bidIncrement;

    @Column(name = "current_price", precision = 15, scale = 2)
    private BigDecimal currentPrice;

    @Column(name = "highest_bidder_id")
    private UUID highestBidderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private AuctionVisibility visibility = AuctionVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuctionStatus status = AuctionStatus.SCHEDULED;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "extension_count", nullable = false)
    private Integer extensionCount = 0;

    @Version
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    @Transient
    private Instant createdAt = Instant.now();

    public Auction() {
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Auction auction = (Auction) o;
        return Objects.equals(id, auction.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}



