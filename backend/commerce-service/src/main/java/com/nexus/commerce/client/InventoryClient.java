package com.nexus.commerce.client;

import java.util.List;
import java.util.UUID;

/** Cổng Commerce gọi Fulfillment bằng JWT đã xác thực; reserve theo orderId, release dùng lại cùng ref. */
public interface InventoryClient {
    int available(UUID skuId);
    /** Trả các reservationId đã xác minh để Checkout lưu snapshot phục vụ confirm sau thanh toán. */
    List<UUID> reserve(UUID orderId, List<Item> items, String token);
    void release(UUID orderId, String token);
    /** Worker dùng token service account; xác nhận lặp phải không trừ kho hai lần. */
    void confirm(UUID reservationId, String token);

    /** warehouseId null yêu cầu kho mặc định của Fulfillment, không tự chọn kho tại Commerce. */
    record Item(UUID skuId, UUID warehouseId, int quantity) { }
}
