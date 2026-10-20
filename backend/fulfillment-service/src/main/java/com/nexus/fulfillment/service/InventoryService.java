package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import java.util.UUID;

/**
 * Giao diện dịch vụ quản lý tồn kho và phiếu giữ hàng (Inventory Service).
 *
 * Chức năng: Cung cấp các thao tác nghiệp vụ cho toàn bộ chu trình xử lý tồn kho theo SRS §3.6.1:
 * 1. Kiểm tra tồn kho khả dụng trước khi đặt hàng.
 * 2. Tạo phiếu giữ hàng tạm thời khi người dùng checkout.
 * 3. Tra cứu thông tin phiếu giữ hàng.
 * 4. Xác nhận xuất kho (Commit) khi thanh toán thành công (InventoryCommitted).
 * 5. Hoàn kho và giải phóng hàng (Release) khi đơn bị hủy hoặc quá hạn (InventoryReleased).
 * 6. Tra cứu tồn kho chi tiết theo SKU.
 */
public interface InventoryService {

    /**
     * Kiểm tra số lượng tồn kho khả dụng cho danh sách các SKU yêu cầu.
     *
     * @param request Danh sách các SKU, kho hàng và số lượng cần kiểm tra
     * @return Kết quả kiểm tra tổng quát và chi tiết từng SKU
     */
    InventoryCheckResponse checkStock(InventoryCheckRequest request);

    /**
     * Tạo phiếu giữ hàng tồn kho tạm thời cho đơn hàng / phiên đấu giá.
     *
     * @param request Thông tin đơn hàng, danh sách SKU, số lượng và thời gian hết hạn
     * @return Thông tin phiếu giữ hàng ở trạng thái PENDING
     * @throws com.nexus.fulfillment.exception.InsufficientStockException Khi không đủ tồn khả dụng
     * @throws com.nexus.fulfillment.exception.SkuNotFoundException Khi không tìm thấy SKU
     */
    ReservationResponse createReservation(CreateReservationRequest request);

    /**
     * Tra cứu chi tiết phiếu giữ hàng theo ID.
     *
     * @param reservationId Mã định danh phiếu giữ hàng
     * @return Thông tin chi tiết phiếu giữ hàng
     * @throws com.nexus.fulfillment.exception.ReservationNotFoundException Khi không tìm thấy phiếu
     */
    ReservationResponse getReservation(UUID reservationId);

    /**
     * Xác nhận xuất kho chính thức sau khi đơn hàng thanh toán thành công.
     *
     * @param reservationId Mã định danh phiếu giữ hàng
     * @param request       Lý do hoặc thông tin xác nhận
     * @return Thông tin phiếu giữ hàng đã cập nhật sang trạng thái CONFIRMED
     * @throws com.nexus.fulfillment.exception.ReservationNotFoundException Khi không tìm thấy phiếu
     * @throws com.nexus.fulfillment.exception.ReservationExpiredException  Khi phiếu đã quá hạn giữ hàng
     * @throws com.nexus.fulfillment.exception.InvalidReservationStatusException Khi trạng thái không hợp lệ
     */
    ReservationResponse commitReservation(UUID reservationId, CommitReservationRequest request);

    /**
     * Hủy giữ hàng và hoàn trả số lượng khả dụng vào kho khi đơn bị hủy hoặc hết hạn thanh toán.
     *
     * @param reservationId Mã định danh phiếu giữ hàng
     * @param request       Lý do hủy giữ hàng
     * @return Thông tin phiếu giữ hàng đã cập nhật sang trạng thái RELEASED
     * @throws com.nexus.fulfillment.exception.ReservationNotFoundException Khi không tìm thấy phiếu
     * @throws com.nexus.fulfillment.exception.InvalidReservationStatusException Khi phiếu đã CONFIRMED
     */
    ReservationResponse releaseReservation(UUID reservationId, ReleaseReservationRequest request);

    /**
     * Tra cứu số lượng tồn kho (tổng, đang giữ, không khả dụng, khả dụng) của một SKU.
     *
     * @param skuId       Mã định danh SKU
     * @param warehouseId Mã kho hàng (tùy chọn)
     * @return Thông tin số lượng tồn kho
     * @throws com.nexus.fulfillment.exception.SkuNotFoundException Khi SKU không tồn tại
     */
    SkuInventoryResponse getSkuInventory(UUID skuId, UUID warehouseId);
}
