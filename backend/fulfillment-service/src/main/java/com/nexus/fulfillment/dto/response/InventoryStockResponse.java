package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Phản hồi chi tiết 4 trạng thái tồn kho của SKU theo từng kho hàng, kèm thông tin seller_skus của Fulfillment.
 *
 * @param skuId          Mã định danh biến thể SKU từ Catalog
 * @param sellerSkuId    Mã định danh bản ghi seller_skus trong Fulfillment (nullable nếu chưa backfill trong unit test)
 * @param skuCode        Mã SKU nội bộ do Fulfillment cấp phát khi nhập kho lần đầu (ví dụ FSKU-00000001)
 * @param warehouseId    Mã định danh kho hàng
 * @param warehouseCode  Mã nghiệp vụ của kho (ví dụ WH_001)
 * @param totalQty       Tổng số lượng tồn thực tế trong kho
 * @param availableQty   Số lượng khả dụng để bán (= total - reserved - unavailable)
 * @param reservedQty    Số lượng đang bị tạm giữ bởi các đơn Checkout
 * @param unavailableQty Số lượng hàng hỏng hóc, cách ly không được bán
 */
public record InventoryStockResponse(
        UUID skuId,
        UUID sellerSkuId,
        String skuCode,
        UUID warehouseId,
        String warehouseCode,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty
) {
    /** Constructor 7 tham số giữ tương thích với test/caller cũ chưa cần sellerSkuId và skuCode. */
    public InventoryStockResponse(
            UUID skuId,
            UUID warehouseId,
            String warehouseCode,
            int totalQty,
            int availableQty,
            int reservedQty,
            int unavailableQty) {
        this(skuId, null, null, warehouseId, warehouseCode, totalQty, availableQty, reservedQty, unavailableQty);
    }
}
