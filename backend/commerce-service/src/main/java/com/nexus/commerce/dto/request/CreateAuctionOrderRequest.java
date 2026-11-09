package com.nexus.commerce.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu khởi tạo đơn hàng từ kết quả phiên đấu giá thành công (SRS §3.5.3 & §3.4.3).
 *
 * Chức năng:
 * - Đóng gói toàn bộ dữ liệu quyết toán (Settlement Data) được bàn giao từ Auction Service sang Commerce Service.
 * - Dùng để tạo đơn hàng chính thức cho người thắng cuộc với sourceType = AUCTION và hạn thanh toán 24h.
 *
 * Đầu vào và kết quả:
 * - Nhận: auctionId, winnerId (Buyer), sellerId, productId, skuId, finalPrice, paymentDeadline, productName, skuCode.
 * - Kết quả: Commerce Service tạo hoặc trả về đơn hàng hiện hữu (Idempotent).
 *
 * Quy tắc và lý do:
 * - auctionId đóng vai trò là Idempotency Key tự nhiên để triệt tiêu nguy cơ sinh trùng đơn hàng khi có replay.
 * - finalPrice bắt buộc > 0 (tối thiểu 0.01 VND) tương ứng với mức giá thắng cuộc hợp lệ.
 */
public record CreateAuctionOrderRequest(
        @NotNull(message = "Mã phiên đấu giá không được để trống")
        UUID auctionId,

        @NotNull(message = "Mã người thắng cuộc không được để trống")
        UUID winnerId,

        @NotNull(message = "Mã người bán không được để trống")
        UUID sellerId,

        UUID productId,

        @NotNull(message = "Mã SKU không được để trống")
        UUID skuId,

        @NotNull(message = "Giá trúng đấu giá không được để trống")
        @DecimalMin(value = "0.01", message = "Giá trúng đấu giá phải lớn hơn 0")
        BigDecimal finalPrice,

        Instant paymentDeadline,

        String productName,

        String skuCode
) {
    /**
     * Constructor thuận tiện cho các caller nội bộ chỉ truyền các trường tối thiểu bắt buộc.
     */
    public CreateAuctionOrderRequest(
            UUID auctionId,
            UUID winnerId,
            UUID sellerId,
            UUID skuId,
            BigDecimal finalPrice,
            Instant paymentDeadline) {
        this(auctionId, winnerId, sellerId, null, skuId, finalPrice, paymentDeadline, null, null);
    }
}

