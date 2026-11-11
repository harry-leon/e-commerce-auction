package com.nexus.fulfillment.dto.response;

import java.util.UUID;

/**
 * Kết quả đối soát giữa số dư tồn kho hiện tại và tổng biến động trong sổ cái bất biến (Inventory Ledger).
 *
 * @param skuId                Mã SKU
 * @param warehouseId          Mã kho
 * @param warehouseCode        Mã nghiệp vụ của kho
 * @param totalQty             Tổng tồn kho hiện lưu trên bảng inventories
 * @param availableQty         Tồn khả dụng hiện tại
 * @param reservedQty          Số lượng đang giữ trên bảng inventories
 * @param unavailableQty       Số lượng hàng lỗi/cách ly
 * @param ledgerTotalQty       Tổng tồn vật lý tính lại từ sổ cái (INBOUND - OUTBOUND + ADJUSTMENT)
 * @param ledgerReservedQty    Tổng giữ kho tính lại từ sổ cái (RESERVE - RELEASE - OUTBOUND)
 * @param activeReservationQty Tổng số lượng phiếu giữ kho đang ở trạng thái PENDING
 * @param nonNegative          True nếu không âm tồn và thỏa mãn totalQty >= reservedQty + unavailableQty
 * @param matched              True nếu mọi chỉ số tồn kho khớp hoàn toàn với sổ cái và phiếu giữ kho
 */
public record InventoryReconciliationResponse(
        UUID skuId,
        UUID warehouseId,
        String warehouseCode,
        int totalQty,
        int availableQty,
        int reservedQty,
        int unavailableQty,
        int ledgerTotalQty,
        int ledgerReservedQty,
        int activeReservationQty,
        boolean nonNegative,
        boolean matched
) {
}
