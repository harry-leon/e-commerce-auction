package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Phản hồi chi tiết 4 trạng thái tồn kho của SKU theo từng kho hàng, kèm thông tin seller_skus của Fulfillment.
 *
 * @param skuId          Mã định danh biến thể SKU từ Catalog
 * @param sellerSkuId    Mã định danh bản ghi seller_skus trong Fulfillment (nullable trong unit test chưa gắn seller_sku)
 * @param skuCode        Mã SKU nội bộ do Fulfillment cấp phát khi nhập kho lần đầu (ví dụ FSKU-00000001)
 * @param warehouseId    Mã định danh kho hàng
 * @param warehouseCode  Mã nghiệp vụ của kho (ví dụ WH_001)
 * @param warehouseName  Tên hiển thị kho hàng (có thể null với bản ghi chưa gắn tên kho)
 * @param totalQty       Tổng số lượng tồn thực tế trong kho
 * @param availableQty   Số lượng khả dụng để bán (= total - reserved - unavailable)
 * @param reservedQty    Số lượng đang bị tạm giữ bởi các đơn Checkout / Đấu giá
 * @param unavailableQty Số lượng hàng hỏng hóc, cách ly không được bán
 */
public record InventoryStockResponse(
        UUID skuId,
        UUID sellerSkuId,
        String skuCode,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty
) {
    /** Constructor 7 tham số giữ tương thích ngược với test/caller chỉ dùng các chỉ số tồn kho cơ bản. */
    public InventoryStockResponse(
            UUID skuId,
            UUID warehouseId,
            String warehouseCode,
            int totalQty,
            int availableQty,
            int reservedQty,
            int unavailableQty
    ) {
        this(skuId, null, null, warehouseId, warehouseCode, null, totalQty, availableQty, reservedQty, unavailableQty);
    }

    /** Constructor 9 tham số giữ tương thích với caller truyền sellerSkuId + skuCode nhưng chưa có warehouseName. */
    public InventoryStockResponse(
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
        this(skuId, sellerSkuId, skuCode, warehouseId, warehouseCode, null, totalQty, availableQty, reservedQty, unavailableQty);
    }

    /** Constructor 9 tham số giữ tương thích với caller truyền warehouseName + skuCode nhưng chưa có sellerSkuId. */
    public InventoryStockResponse(
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
        this(skuId, null, skuCode, warehouseId, warehouseCode, warehouseName, totalQty, availableQty, reservedQty, unavailableQty);
    }
}
