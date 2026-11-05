package com.nexus.auction.dto.request;

import com.nexus.auction.enums.AuctionVisibility;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO yêu cầu tạo phiên đấu giá mới từ Người bán (Seller).
 *
 * Chức năng:
 * - Tiếp nhận thông tin cấu hình phiên đấu giá: sản phẩm, SKU, giá khởi điểm, bước giá,
 *   thời gian bắt đầu, thời gian kết thúc và chế độ hiển thị (PUBLIC/PRIVATE).
 *
 * Quy tắc và lý do:
 * - Bắt buộc có productId, startPrice, bidIncrement, startTime, endTime.
 * - Không truyền sellerId từ client; server tự trích xuất từ JWT principal để chống IDOR.
 * - Dùng validation tiêu chuẩn (@NotNull, @DecimalMin) để bắt lỗi cú pháp sớm.
 */
public class CreateAuctionRequest {

    @NotNull(message = "Mã sản phẩm (productId) không được để trống")
    private UUID productId;

    private UUID skuId;

    @NotNull(message = "Giá khởi điểm (startPrice) không được để trống")
    @DecimalMin(value = "1.00", message = "Giá khởi điểm phải lớn hơn 0")
    private BigDecimal startPrice;

    @NotNull(message = "Bước giá (bidIncrement) không được để trống")
    @DecimalMin(value = "1.00", message = "Bước giá phải lớn hơn 0")
    private BigDecimal bidIncrement;

    @NotNull(message = "Thời gian bắt đầu (startTime) không được để trống")
    private Instant startTime;

    @NotNull(message = "Thời gian kết thúc (endTime) không được để trống")
    private Instant endTime;

    private AuctionVisibility visibility = AuctionVisibility.PUBLIC;

    public CreateAuctionRequest() {
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

