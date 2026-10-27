package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.enums.ReservationRefType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện dịch vụ quản trị tồn kho cấp SKU và giữ hàng Checkout (SRS §3.6.1).
 *
 * Chức năng:
 * - Theo dõi 4 trạng thái tồn kho chính xác theo SKU và kho hàng.
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với cơ chế chống bán vượt (ALLOW_OVERSELL = false).
 * - Kiểm soát truy cập đồng thời (Concurrency Control) qua khóa dòng dữ liệu.
 * - Cơ chế tự động giải phóng phiếu giữ hàng quá hạn sau 30 phút.
 * - Ghi nhận mọi biến động vào Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 */
public interface InventoryService {

    /**
     * Tra cứu thông tin 4 trạng thái tồn kho của SKU tại một kho hàng cụ thể (hoặc kho mặc định).
     */
    InventoryStockResponse getStockBySku(UUID skuId, UUID warehouseId);

    /**
     * Tra cứu danh sách tồn kho của SKU trên tất cả các kho đang lưu trữ.
     */
    List<InventoryStockResponse> getAllStockBySku(UUID skuId);

    /**
     * Kiểm tra nhanh số lượng tồn khả dụng cho danh sách SKU (dùng cho Cart và Checkout validation).
     */
    List<SkuAvailabilityResponse> checkAvailability(SkuAvailabilityCheckRequest request);

    /**
     * Khóa giữ hàng tồn kho khi người dùng Checkout (Atomic Reservation).
     * Áp dụng Pessimistic Locking và cơ chế chống Overselling.
     */
    ReservationResponse reserveStock(CreateReservationRequest request);

    /**
     * Xác nhận đơn hàng đã thanh toán thành công (Commit Reservation), trừ tồn kho vật lý và chuyển thành OUTBOUND.
     */
    ReservationResponse confirmReservation(UUID reservationId);

    /**
     * Giải phóng một phiếu giữ hàng cụ thể theo reservationId, hoàn trả số lượng về tồn khả dụng.
     */
    void releaseReservation(UUID reservationId, String reason);

    /**
     * Giải phóng toàn bộ các phiếu giữ hàng gắn với mã tham chiếu đơn hàng/phiên đấu giá (ref_id).
     */
    void releaseReservationsByRef(ReleaseReservationRequest request);

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn 30 phút.
     *
     * @return Số lượng phiếu giữ hàng đã được giải phóng
     */
    int autoReleaseExpiredReservations();

    /**
     * Nhập thêm hàng hóa vào kho cho SKU và ghi sổ cái INBOUND.
     */
    InventoryStockResponse inboundStock(StockInboundRequest request);

    /**
     * Điều chỉnh kiểm kê tồn kho thực tế và ghi sổ cái ADJUSTMENT.
     */
    InventoryStockResponse adjustStock(StockAdjustmentRequest request);

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến (chỉ đọc, phân trang, lọc theo SKU hoặc ref_id).
     */
    Page<InventoryLedgerResponse> getLedger(UUID skuId, UUID refId, Pageable pageable);
}
