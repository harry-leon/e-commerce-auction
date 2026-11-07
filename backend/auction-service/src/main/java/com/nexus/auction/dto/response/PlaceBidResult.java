package com.nexus.auction.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO kết quả sau khi đặt giá thầu thành công (Place Bid Result).
 *
 * Chức năng:
 * - Trả về chi tiết lượt bid vừa đặt.
 * - Cập nhật giá cao nhất hiện tại (currentBid/currentPrice), tổng số lượt bid (bidCount).
 * - Cung cấp thông tin gia hạn giờ chót (endTime mới, extended = true/false) theo anti-sniping.
 */
@Schema(description = "Kết quả đặt giá thầu thành công")
public class PlaceBidResult {

    @Schema(description = "Thông tin chi tiết lượt bid vừa ghi nhận")
    private BidResponse bid;

    @Schema(description = "Mức giá thầu cao nhất hiện tại", example = "5200000.00")
    private BigDecimal currentBid;

    @Schema(description = "Tổng số lượt bid hợp lệ của phiên", example = "5")
    private long bidCount;

    @Schema(description = "Thời gian kết thúc phiên (đã cập nhật nếu có gia hạn)", example = "2026-11-07T20:02:00Z")
    private Instant endTime;

    @Schema(description = "Cờ đánh dấu phiên có vừa được gia hạn chống sniping hay không", example = "true")
    private boolean extended;

    public PlaceBidResult() {
    }

    public PlaceBidResult(BidResponse bid, BigDecimal currentBid, long bidCount, Instant endTime, boolean extended) {
        this.bid = bid;
        this.currentBid = currentBid;
        this.bidCount = bidCount;
        this.endTime = endTime;
        this.extended = extended;
    }

    public BidResponse getBid() {
        return bid;
    }

    public void setBid(BidResponse bid) {
        this.bid = bid;
    }

    public BigDecimal getCurrentBid() {
        return currentBid;
    }

    public void setCurrentBid(BigDecimal currentBid) {
        this.currentBid = currentBid;
    }

    public long getBidCount() {
        return bidCount;
    }

    public void setBidCount(long bidCount) {
        this.bidCount = bidCount;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public boolean isExtended() {
        return extended;
    }

    public void setExtended(boolean extended) {
        this.extended = extended;
    }
}

