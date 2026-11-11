package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.BatchStockRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryReconciliationResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SellerSkuDetailResponse;
import com.nexus.fulfillment.dto.response.SellerSkuResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.enums.SellerSkuStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện dịch vụ quản trị tồn kho cấp SKU và giữ hàng Checkout / Đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Theo dõi 4 trạng thái tồn kho chính xác theo SKU và kho hàng; hỗ trợ tra cứu theo skuCode, danh sách phân trang và batch.
 * - Kiểm tra quyền thao tác và phạm vi dữ liệu theo người bán (Seller) thông qua Catalog Service.
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với cơ chế chống bán vượt (ALLOW_OVERSELL = false) và chống lặp theo refId.
 * - Quản lý toàn bộ vòng đời tồn kho của phiên đấu giá và đơn hàng.
 * - Kiểm soát truy cập đồng thời (Concurrency Control) qua khóa dòng dữ liệu bi quan (Pessimistic Write Lock).
 * - Tự động giải phóng phiếu giữ hàng quá hạn và đối soát khớp giữa tồn kho với sổ cái bất biến.
 */
public interface InventoryService {

    /**
     * Tra cứu thông tin 4 trạng thái tồn kho của SKU tại một kho hàng cụ thể (hoặc kho mặc định).
     */
    InventoryStockResponse getStockBySku(UUID skuId, UUID warehouseId);

    /**
     * Tra cứu tồn kho của SKU theo mã nghiệp vụ skuCode tại kho chỉ định hoặc kho mặc định.
     */
    InventoryStockResponse getStockBySkuCode(String skuCode, UUID warehouseId);

    /**
     * Tra cứu danh sách tồn kho của SKU trên tất cả các kho đang lưu trữ.
     */
    List<InventoryStockResponse> getAllStockBySku(UUID skuId);

    /**
     * Đọc hàng loạt 4 trạng thái tồn kho cho danh sách SKU tại một kho hàng.
     */
    List<InventoryStockResponse> getBatchStock(BatchStockRequest request);

    /**
     * Liệt kê và lọc tồn kho có phân trang theo SKU, kho hàng và phạm vi seller.
     */
    Page<InventoryStockResponse> listInventory(
            UUID skuId,
            String skuCode,
            UUID warehouseId,
            UUID sellerId,
            Pageable pageable
    );

    /**
     * Kiểm tra nhanh số lượng tồn khả dụng cho danh sách SKU (dùng cho Cart và Checkout validation).
     */
    List<SkuAvailabilityResponse> checkAvailability(SkuAvailabilityCheckRequest request);

    /**
     * Khóa giữ hàng tồn kho khi người dùng Checkout (Atomic Reservation).
     * Áp dụng Pessimistic Locking, idempotent theo (refType, refId) và chống Overselling.
     */
    ReservationResponse reserveStock(CreateReservationRequest request);

    /**
     * Khóa giữ hàng chuyên biệt khi Seller tạo phiên đấu giá (Auction Reservation - SRS §3.6.1).
     */
    ReservationResponse reserveAuctionStock(UUID auctionId, ReserveAuctionInventoryRequest request);

    /**
     * Gia hạn thời gian giữ kho khi phiên đấu giá bị kéo dài do Anti-sniping (SRS §2.6 & §3.6.1).
     */
    ReservationResponse extendAuctionReservation(UUID auctionId, ExtendAuctionReservationRequest request);

    /**
     * Xác nhận đơn hàng đã thanh toán thành công (Commit Reservation), trừ tồn kho vật lý và chuyển thành OUTBOUND.
     */
    ReservationResponse confirmReservation(UUID reservationId);

    /**
     * Xác nhận trừ kho thực tế cho toàn bộ các mặt hàng của đơn hàng khi thanh toán thành công (SRS §3.6.1).
     */
    ReservationResponse commitByOrder(UUID orderId);

    /**
     * Giải phóng một phiếu giữ hàng cụ thể theo reservationId, hoàn trả số lượng về tồn khả dụng.
     */
    void releaseReservation(UUID reservationId, String reason);

    /**
     * Giải phóng toàn bộ các phiếu giữ hàng gắn với mã tham chiếu đơn hàng/phiên đấu giá (ref_id).
     */
    void releaseReservationsByRef(ReleaseReservationRequest request);

    /**
     * Giải phóng toàn bộ hàng giữ kho cho đơn hàng khi thanh toán thất bại hoặc đơn bị hủy (SRS §3.6.1).
     */
    void releaseByOrder(UUID orderId, String reason);

    /**
     * Giải phóng tồn kho phiên đấu giá khi bị hủy hoặc thất bại không có người đặt giá (SRS §3.6.1).
     */
    void releaseByAuction(UUID auctionId, String reason);

    /**
     * Chuyển tiếp trạng thái giữ kho từ phiên đấu giá sang Đơn hàng của người thắng cuộc (Transition to Order).
     */
    ReservationResponse transitionAuctionToOrder(UUID auctionId, TransitionAuctionToOrderRequest request);

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn.
     */
    int autoReleaseExpiredReservations();

    /**
     * Nhập thêm hàng hóa vào kho cho SKU và ghi sổ cái INBOUND.
     */
    InventoryStockResponse inboundStock(StockInboundRequest request);

    /**
     * Nhập thêm hàng hóa vào kho hỗ trợ khóa idempotency ở header HTTP.
     */
    InventoryStockResponse inboundStock(StockInboundRequest request, String idempotencyKey);

    /**
     * Điều chỉnh kiểm kê tồn kho thực tế và ghi sổ cái ADJUSTMENT.
     */
    InventoryStockResponse adjustStock(StockAdjustmentRequest request);

    /**
     * Điều chỉnh tồn kho thực tế hỗ trợ khóa idempotency ở header HTTP.
     */
    InventoryStockResponse adjustStock(StockAdjustmentRequest request, String idempotencyKey);

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến (chỉ đọc, phân trang, lọc theo SKU hoặc ref_id).
     */
    Page<InventoryLedgerResponse> getLedger(UUID skuId, UUID refId, Pageable pageable);

    /**
     * Đọc lịch sử sổ cái tồn kho bất biến với đầy đủ bộ lọc theo SKU/kho/loại biến động/thời gian và phạm vi seller.
     */
    Page<InventoryLedgerResponse> getLedger(
            UUID skuId,
            String skuCode,
            UUID warehouseId,
            LedgerMovementType movementType,
            String refType,
            UUID refId,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    );

    /**
     * Đối soát số dư tồn kho với tổng lịch sử biến động trong sổ cái bất biến và các phiếu giữ hàng PENDING.
     */
    List<InventoryReconciliationResponse> reconcileStock(UUID skuId, UUID warehouseId);

    /**
     * Tra cứu danh sách seller_skus và tổng tồn kho các kho trực thuộc theo quyền Seller/Admin.
     */
    Page<SellerSkuResponse> getSellerSkus(String keyword, SellerSkuStatus status, UUID sellerId, Pageable pageable);

    /**
     * Tra cứu chi tiết một seller_skus theo id hoặc skuId kèm tồn kho theo từng kho hàng.
     */
    SellerSkuDetailResponse getSellerSkuDetail(UUID id);
}
