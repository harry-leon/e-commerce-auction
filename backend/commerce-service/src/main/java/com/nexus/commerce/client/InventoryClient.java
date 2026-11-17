package com.nexus.commerce.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cổng Commerce gọi Fulfillment bằng JWT đã xác thực; reserve theo orderId, release dùng lại cùng ref. */
public interface InventoryClient {
    int available(UUID skuId);

    /**
     * Đọc tồn khả dụng thật của nhiều SKU để hiển thị/kiểm tra giỏ hàng.
     * Dùng endpoint batch công khai của Fulfillment; SKU chưa có bản ghi kho trả 0.
     * Lỗi hạ tầng ném ApiException INVENTORY_UNAVAILABLE để caller quyết định bỏ qua kiểm tra thay vì chặn oan.
     */
    Map<UUID, Integer> availability(List<UUID> skuIds);
    /** Trả các reservationId đã xác minh để Checkout lưu snapshot phục vụ confirm sau thanh toán. */
    List<UUID> reserve(UUID orderId, List<Item> items, String token);
    void release(UUID orderId, String token);
    /** Worker dùng token service account; xác nhận lặp phải không trừ kho hai lần. */
    void confirm(UUID reservationId, String token);

    /** Commit nguyên tử toàn bộ phiếu ORDER; Fulfillment khóa cùng dòng với sweeper, replay không trừ kho lần hai. */
    void commitOrder(UUID orderId, List<UUID> expectedReservations, String token);

    /** warehouseId null yêu cầu kho mặc định của Fulfillment, không tự chọn kho tại Commerce. */
    record Item(UUID skuId, UUID warehouseId, int quantity) { }
}
