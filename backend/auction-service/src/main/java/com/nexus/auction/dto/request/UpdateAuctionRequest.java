package com.nexus.auction.dto.request;

import com.nexus.auction.enums.AuctionVisibility;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * DTO yêu cầu cập nhật thông số phiên đấu giá trước giờ bắt đầu.
 *
 * Chức năng:
 * - Tiếp nhận các trường được phép chỉnh sửa: giá khởi điểm, bước giá, thời gian bắt đầu/kết thúc, visibility.
 *
 * Quy tắc và lý do:
 * - Chỉ được cập nhật khi phiên chưa bắt đầu (now < startTime) và trạng thái là SCHEDULED.
 * - Các trường là tùy chọn (null nghĩa là giữ nguyên giá trị cũ).
 */
public class UpdateAuctionRequest {

    @DecimalMin(value = "1.00", message = "Giá khởi điểm phải lớn hơn 0")
    private BigDecimal startPrice;

    @DecimalMin(value = "1.00", message = "Bước giá phải lớn hơn 0")
    private BigDecimal bidIncrement;

    private Instant startTime;

    private Instant endTime;

    private AuctionVisibility visibility;

    public UpdateAuctionRequest() {
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

    public AuctionVisibility getVisibility() {
        return visibility;
    }

    public void setVisibility(AuctionVisibility visibility) {
        this.visibility = visibility;
    }
}

