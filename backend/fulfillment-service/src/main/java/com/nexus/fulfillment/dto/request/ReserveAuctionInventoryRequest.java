package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu khóa giữ hàng tồn kho khi Seller tạo phiên đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Nhận mã SKU, kho hàng, thời gian bắt đầu và kết thúc của phiên đấu giá để khóa giữ đúng 1 sản phẩm.
 * - Thiết lập TTL của phiếu giữ kho bao quát toàn bộ thời gian diễn ra phiên đấu giá (từ startTime đến endTime).
 *
 * Đầu vào và kết quả:
 * - Đầu vào: skuId, warehouseId (tùy chọn), startTime, endTime, quantity (mặc định 1).
 * - Kết quả: Được xử lý bởi InventoryService để tạo InventoryReservation loại AUCTION.
 *
 * Quy tắc và lý do:
 * - Mỗi phiên đấu giá chỉ giữ đúng 1 đơn vị SKU (quantity = 1 theo SRS §3.6.1 và phân công S2).
 * - Thời gian kết thúc phải lớn hơn thời gian bắt đầu và thời lượng nằm trong giới hạn cấu hình.
 *
 * @param skuId        Mã SKU sản phẩm cần đưa vào đấu giá
 * @param warehouseId  Mã kho hàng lưu trữ (tùy chọn, mặc định là kho WH_001)
 * @param startTime    Thời gian bắt đầu phiên đấu giá
 * @param endTime      Thời gian kết thúc phiên đấu giá (dùng làm mốc TTL expires_at của phiếu giữ kho)
 * @param quantity     Số lượng giữ kho (mặc định 1 cho phiên đấu giá)
 */
public record ReserveAuctionInventoryRequest(
        @NotNull(message = "skuId không được để trống")
        UUID skuId,

        UUID warehouseId,

        @NotNull(message = "startTime không được để trống")
        Instant startTime,

        @NotNull(message = "endTime không được để trống")
        Instant endTime,

        Integer quantity
) {
    public ReserveAuctionInventoryRequest {
        if (quantity == null || quantity <= 0) {
            quantity = 1;
        }
    }
}

