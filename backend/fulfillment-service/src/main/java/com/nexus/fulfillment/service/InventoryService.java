package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.ReserveAuctionInventoryRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.request.TransitionAuctionToOrderRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện dịch vụ quản trị tồn kho cấp SKU và giữ hàng Checkout / Đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Theo dõi 4 trạng thái tồn kho chính xác theo SKU và kho hàng.
 * - Khóa giữ hàng nguyên tử (Atomic Reservation) với cơ chế chống bán vượt (ALLOW_OVERSELL = false).
 * - Quản lý toàn bộ vòng đời tồn kho của phiên đấu giá: Khóa giữ kho khi tạo phiên, gia hạn khi Anti-sniping,
 *   giải phóng kho khi hủy/thất bại, và chuyển tiếp trực tiếp sang Đơn hàng của Winner mà không bị Reserve kép.
 * - Kiểm soát truy cập đồng thời (Concurrency Control) qua khóa dòng dữ liệu bi quan (Pessimistic Write Lock).
 * - Cơ chế tự động giải phóng phiếu giữ hàng quá hạn.
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
     * Khóa giữ hàng chuyên biệt khi Seller tạo phiên đấu giá (Auction Reservation - SRS §3.6.1).
     *
     * Chức năng:
     * - Kiểm tra và giữ kho cho sản phẩm đấu giá với loại tham chiếu AUCTION.
     * - Thiết lập TTL của phiếu giữ kho bao quát toàn bộ thời gian diễn ra phiên đấu giá (từ start_time đến end_time).
     * - Nghiêm cấm bán vượt số lượng tồn (ALLOW_OVERSELL = false), từ chối kích hoạt phiên nếu tồn kho khả dụng không đủ.
     * - Đảm bảo tính Idempotent: nếu đã giữ kho cho cùng auctionId thì trả về kết quả hiện tại, không giữ trùng lặp.
     *
     * @param auctionId Mã phiên đấu giá
     * @param request   Thông tin SKU, kho, thời gian bắt đầu và kết thúc phiên đấu giá
     * @return Phản hồi thông tin phiếu giữ kho AUCTION (PENDING)
     */
    ReservationResponse reserveAuctionStock(UUID auctionId, ReserveAuctionInventoryRequest request);

    /**
     * Gia hạn thời gian giữ kho khi phiên đấu giá bị kéo dài do Anti-sniping (SRS §2.6 & §3.6.1).
     *
     * Chức năng:
     * - Tiếp nhận tín hiệu gia hạn khi phiên đấu giá bị kéo dài (mặc định ANTI_SNIPING_EXTENSION_MINUTES = 5 phút).
     * - Tự động kéo dài thời hạn hết hạn (expires_at) của phiếu giữ kho tương ứng, ngăn chặn kho bị giải phóng nhầm khi phiên vẫn đang mở.
     *
     * @param auctionId Mã phiên đấu giá
     * @param request   Thông tin số phút gia hạn hoặc mốc kết thúc mới
     * @return Phản hồi thông tin phiếu giữ kho đã được gia hạn
     */
    ReservationResponse extendAuctionReservation(UUID auctionId, ExtendAuctionReservationRequest request);

    /**
     * Xác nhận đơn hàng đã thanh toán thành công (Commit Reservation), trừ tồn kho vật lý và chuyển thành OUTBOUND.
     * Đảm bảo Idempotent: nếu đã xác nhận trước đó thì không trừ kho lần hai.
     */
    ReservationResponse confirmReservation(UUID reservationId);

    /**
     * Xác nhận trừ kho thực tế cho toàn bộ các mặt hàng của đơn hàng khi thanh toán thành công (SRS §3.6.1).
     * Trừ vĩnh viễn reserved_qty và total_qty, ghi sổ cái OUTBOUND và phát sinh sự kiện InventoryCommittedEvent.
     * Đảm bảo tính Idempotent: nếu nhận trùng webhook hoặc retry sự kiện nhiều lần thì tồn kho vẫn chỉ bị trừ 1 lần duy nhất.
     *
     * @param orderId Mã đơn hàng cần xác nhận xuất kho
     * @return Phản hồi thông tin giữ kho đã được xác nhận (CONFIRMED)
     */
    ReservationResponse commitByOrder(UUID orderId);

    /**
     * Giải phóng một phiếu giữ hàng cụ thể theo reservationId, hoàn trả số lượng về tồn khả dụng.
     * Đảm bảo Idempotent: nếu đã RELEASED hoặc EXPIRED thì không hoàn kho lần hai.
     */
    void releaseReservation(UUID reservationId, String reason);

    /**
     * Giải phóng toàn bộ các phiếu giữ hàng gắn với mã tham chiếu đơn hàng/phiên đấu giá (ref_id).
     */
    void releaseReservationsByRef(ReleaseReservationRequest request);

    /**
     * Giải phóng toàn bộ hàng giữ kho cho đơn hàng khi thanh toán thất bại hoặc đơn bị hủy (SRS §3.6.1).
     * Giảm reserved_qty để hoàn trả đầy đủ số lượng về lại available_qty, ghi sổ cái RELEASE và phát sinh InventoryReleasedEvent.
     * Đảm bảo tính Idempotent: nếu nhận trùng webhook hoặc retry sự kiện nhiều lần thì tồn kho vẫn chỉ được hoàn lại 1 lần duy nhất.
     *
     * @param orderId Mã đơn hàng cần giải phóng
     * @param reason Lý do giải phóng (vd: PAYMENT_FAILED, ORDER_CANCELLED)
     */
    void releaseByOrder(UUID orderId, String reason);

    /**
     * Giải phóng tồn kho phiên đấu giá khi bị hủy hoặc thất bại không có người đặt giá (SRS §3.6.1).
     *
     * Chức năng:
     * - Khi phiên bị Seller/Admin hủy (AuctionCancelled) hoặc kết thúc không có người đặt giá (AuctionFailed):
     *   Tức thời giải phóng số lượng giữ trước, hoàn trả về available_qty trong kho.
     * - Ghi bản ghi biến động vào sổ cái (inventory_ledger) với loại giao dịch RELEASE.
     * - Đảm bảo tính Idempotent: retry release không tăng kho thừa.
     *
     * @param auctionId Mã phiên đấu giá cần giải phóng tồn kho
     * @param reason    Lý do giải phóng (vd: AUCTION_CANCELLED, AUCTION_FAILED, NO_BIDS)
     */
    void releaseByAuction(UUID auctionId, String reason);

    /**
     * Chuyển tiếp trạng thái giữ kho từ phiên đấu giá sang Đơn hàng của người thắng cuộc (Transition to Order) (SRS §3.6.1).
     *
     * Chức năng:
     * - Đảm bảo không xảy ra hiện tượng "Reserve kép" (Double Reservation): Khi chốt người thắng cuộc,
     *   chuyển đổi mã tham chiếu từ auction_id sang order_id mà không cần nhả kho rồi giữ lại từ đầu.
     * - Cập nhật hạn thanh toán mới cho đơn hàng (mặc định 24 giờ kể từ thời điểm chốt).
     * - Đảm bảo tính Idempotent: nếu retry nhiều lần với cùng orderId thì trả về kết quả thành công ngay lập tức.
     *
     * @param auctionId Mã phiên đấu giá đã kết thúc thành công
     * @param request   Mã đơn hàng và hạn thanh toán của người thắng cuộc
     * @return Phản hồi thông tin giữ kho đã được chuyển tiếp sang ORDER (PENDING)
     */
    ReservationResponse transitionAuctionToOrder(UUID auctionId, TransitionAuctionToOrderRequest request);

    /**
     * Quét và tự động hoàn kho các phiếu giữ hàng PENDING đã quá thời hạn.
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
