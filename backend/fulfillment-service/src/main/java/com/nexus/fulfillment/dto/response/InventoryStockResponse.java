package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Phản hồi chi tiết 4 trạng thái tồn kho của SKU theo từng kho hàng (SRS §3.6.1).
 *
 * @param skuId          Mã định danh biến thể SKU
 * @param warehouseId    Mã định danh kho hàng
 * @param warehouseCode  Mã nghiệp vụ của kho (ví dụ WH_001)
 * @param warehouseName  Tên hiển thị kho hàng (có thể null với bản ghi chưa gắn tên kho)
 * @param skuCode        Mã SKU doanh nghiệp nếu được phân giải kèm
 * @param totalQty       Tổng số lượng tồn thực tế trong kho
 * @param availableQty   Số lượng khả dụng để bán (= total - reserved - unavailable)
 * @param reservedQty    Số lượng đang bị tạm giữ bởi các đơn Checkout / Đấu giá
 * @param unavailableQty Số lượng hàng hỏng hóc, cách ly không được bán
 */
public record InventoryStockResponse(
        UUID skuId,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        String skuCode,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty
) {
    /**
     * Constructor tương thích ngược với contract 7 trường ban đầu.
     */
    public InventoryStockResponse(
            UUID skuId,
            UUID warehouseId,
            String warehouseCode,
            int totalQty,
            int availableQty,
            int reservedQty,
            int unavailableQty
    ) {
        this(skuId, warehouseId, warehouseCode, null, null, totalQty, availableQty, reservedQty, unavailableQty);
    }
}
