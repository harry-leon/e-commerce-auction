package com.nexus.commerce.client;

import com.nexus.commerce.client.dto.ReservationItem;
import com.nexus.commerce.client.dto.ReservationResponse;
import java.util.List;
import java.util.UUID;

/**
 * Cổng giao tiếp (Client Port) với Fulfillment / Inventory Service để xử lý giữ hàng và giải phóng tồn kho.
 *
 * <p>Quy tắc và lý do:
 * Tuân thủ SRS §3.4.3 và BR-COM-02:
 * <ul>
 *   <li>Khi đặt hàng, hệ thống Commerce gửi yêu cầu tạo giữ hàng tồn kho (Reservation) trạng thái PENDING với thời hạn 15 phút.</li>
 *   <li>Nếu thanh toán thành công, reservation được chuyển sang CONFIRMED.</li>
 *   <li>Nếu đơn hàng bị hủy hoặc quá hạn 15 phút, reservation được chuyển sang RELEASED để hoàn trả tồn kho khả dụng.</li>
 * </ul>
 */
public interface InventoryClient {

    /**
     * Yêu cầu giữ tồn kho cho các mặt hàng trong đơn hàng với thời hạn mặc định (15 phút).
     *
     * @param orderId ID định danh của đơn hàng
     * @param items   Danh sách các SKU và số lượng cần giữ chỗ
     * @return ReservationResponse chi tiết phiếu giữ kho
     */
    ReservationResponse reserveStock(UUID orderId, List<ReservationItem> items);

    /**
     * Giải phóng lượng tồn kho đã giữ khi đơn hàng bị hủy hoặc hết hạn thanh toán.
     *
     * @param orderId ID định danh của đơn hàng cần giải phóng kho
     */
    void releaseReservation(UUID orderId);
}
