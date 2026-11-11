package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.BatchStockRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ExtendAuctionReservationRequest;
import com.nexus.fulfillment.dto.request.OrderFailureEventRequest;
import com.nexus.fulfillment.dto.request.OrderPaidEventRequest;
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
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.enums.LedgerMovementType;
import com.nexus.fulfillment.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller phục vụ quản lý tồn kho cấp SKU, kho hàng và khóa giữ hàng khi Checkout / Đấu giá (SRS §3.6.1).
 *
 * Chức năng:
 * - Tra cứu tồn kho công khai theo skuId, skuCode hoặc hàng loạt cho Cart/Checkout.
 * - Quản trị đọc tồn kho theo SKU/kho, nhập kho, điều chỉnh kho, xem lịch sử biến động sổ cái và đối soát (Seller được giới hạn theo SKU của mình).
 * - Tiếp nhận các thao tác khóa/xác nhận/giải phóng giữ kho idempotent từ Commerce Service và Auction Service.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory Management", description = "Quản lý tồn kho cấp SKU và giữ hàng Checkout / Đấu giá với kiểm soát đồng thời")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Danh sách tồn kho theo SKU / kho", description = "Trả về tồn kho phân trang; Seller chỉ xem được các SKU thuộc sở hữu của mình")
    public ApiResponse<List<InventoryStockResponse>> listInventory(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) String skuCode,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID sellerId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<InventoryStockResponse> page = inventoryService.listInventory(skuId, skuCode, warehouseId, sellerId, pageable);
        return ApiResponse.ok(page);
    }

    @GetMapping("/sku/{skuId}")
    @Operation(summary = "Xem 4 trạng thái tồn kho của SKU", description = "Trả về total_qty, available_qty, reserved_qty, unavailable_qty tại kho chỉ định hoặc kho mặc định")
    public ApiResponse<InventoryStockResponse> getStockBySku(
            @PathVariable UUID skuId,
            @RequestParam(required = false) UUID warehouseId
    ) {
        InventoryStockResponse response = inventoryService.getStockBySku(skuId, warehouseId);
        return ApiResponse.ok(response);
    }

    @GetMapping("/sku/code/{skuCode}")
    @Operation(summary = "Xem 4 trạng thái tồn kho theo mã skuCode", description = "Phân giải skuCode qua Catalog Service rồi đọc tồn kho tại kho chỉ định hoặc kho mặc định")
    public ApiResponse<InventoryStockResponse> getStockBySkuCode(
            @PathVariable String skuCode,
            @RequestParam(required = false) UUID warehouseId
    ) {
        return ApiResponse.ok(inventoryService.getStockBySkuCode(skuCode, warehouseId));
    }

    @GetMapping("/sku/{skuId}/all")
    @Operation(summary = "Xem tồn kho SKU trên mọi kho hàng", description = "Trả về danh sách trạng thái tồn của SKU trên tất cả các kho")
    public ApiResponse<List<InventoryStockResponse>> getAllStockBySku(@PathVariable UUID skuId) {
        List<InventoryStockResponse> response = inventoryService.getAllStockBySku(skuId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/stocks/batch")
    @Operation(summary = "Đọc tồn kho hàng loạt theo danh sách SKU", description = "Trả về 4 chỉ số tồn kho cho danh sách skuIds tại kho chỉ định hoặc kho mặc định")
    public ApiResponse<List<InventoryStockResponse>> getBatchStock(@Valid @RequestBody BatchStockRequest request) {
        return ApiResponse.ok(inventoryService.getBatchStock(request));
    }

    @PostMapping("/check")
    @Operation(summary = "Kiểm tra số lượng khả dụng hàng loạt", description = "Được gọi bởi Cart hoặc Checkout để xác minh số lượng tồn khả dụng cho danh sách SKU")
    public ApiResponse<List<SkuAvailabilityResponse>> checkAvailability(
            @Valid @RequestBody SkuAvailabilityCheckRequest request
    ) {
        List<SkuAvailabilityResponse> response = inventoryService.checkAvailability(request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/reserve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Khóa giữ hàng khi Checkout (Atomic Reservation)", description = "Khóa tạm giữ hàng tồn kho cho đơn hàng với Concurrency Control và idempotency theo refId")
    public ResponseEntity<ApiResponse<ReservationResponse>> reserveStock(
            @Valid @RequestBody CreateReservationRequest request
    ) {
        ReservationResponse response = inventoryService.reserveStock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/auctions/{auctionId}/reserve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Khóa giữ hàng khi tạo phiên đấu giá (Auction Reservation)", description = "Khóa 1 đơn vị tồn kho khả dụng cho phiên đấu giá với TTL bao quát toàn bộ phiên (SRS §3.6.1)")
    public ResponseEntity<ApiResponse<ReservationResponse>> reserveAuctionStock(
            @PathVariable UUID auctionId,
            @Valid @RequestBody ReserveAuctionInventoryRequest request
    ) {
        ReservationResponse response = inventoryService.reserveAuctionStock(auctionId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/auctions/{auctionId}/extend")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Gia hạn thời gian giữ kho (Anti-sniping Extension)", description = "Kéo dài thời hạn hết hạn (expires_at) của phiếu giữ kho thêm 5 phút hoặc mốc mới khi có Anti-sniping (SRS §2.6 & §3.6.1)")
    public ApiResponse<ReservationResponse> extendAuctionReservation(
            @PathVariable UUID auctionId,
            @Valid @RequestBody(required = false) ExtendAuctionReservationRequest request
    ) {
        ExtendAuctionReservationRequest effectiveRequest = (request != null)
                ? request
                : new ExtendAuctionReservationRequest(null, null);
        ReservationResponse response = inventoryService.extendAuctionReservation(auctionId, effectiveRequest);
        return ApiResponse.ok(response);
    }

    @PostMapping("/auctions/{auctionId}/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Hủy phiên & Giải phóng tồn kho đấu giá", description = "Hoàn trả hàng đã giữ về tồn khả dụng khi phiên bị hủy hoặc không có người đặt giá (Idempotent - SRS §3.6.1)")
    public ApiResponse<Void> releaseByAuction(
            @PathVariable UUID auctionId,
            @RequestParam(required = false, defaultValue = "AUCTION_CANCELLED") String reason
    ) {
        inventoryService.releaseByAuction(auctionId, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/auctions/{auctionId}/transition-to-order")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Chuyển tiếp trạng thái giữ kho sang Đơn hàng (Transition to Order)", description = "Chuyển đổi mã tham chiếu từ auction_id sang order_id của người thắng cuộc mà không bị Reserve kép (SRS §3.6.1)")
    public ApiResponse<ReservationResponse> transitionAuctionToOrder(
            @PathVariable UUID auctionId,
            @Valid @RequestBody TransitionAuctionToOrderRequest request
    ) {
        ReservationResponse response = inventoryService.transitionAuctionToOrder(auctionId, request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xác nhận phiếu giữ kho (Commit/Outbound)", description = "Chốt xuất kho vật lý sau khi đơn hàng thanh toán thành công (Idempotent)")
    public ApiResponse<ReservationResponse> confirmReservation(@PathVariable UUID reservationId) {
        ReservationResponse response = inventoryService.confirmReservation(reservationId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/orders/{orderId}/commit")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xác nhận trừ kho thực tế cho đơn hàng (Commit Order Inventory)", description = "Trừ vĩnh viễn reserved_qty và total_qty cho toàn bộ SKU của đơn khi thanh toán thành công (Idempotent)")
    public ApiResponse<ReservationResponse> commitByOrder(@PathVariable UUID orderId) {
        ReservationResponse response = inventoryService.commitByOrder(orderId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Giải phóng phiếu giữ hàng", description = "Hoàn trả số lượng đang giữ về tồn khả dụng khi người dùng hủy đơn hoặc thanh toán lỗi (Idempotent)")
    public ApiResponse<Void> releaseReservation(
            @PathVariable UUID reservationId,
            @RequestParam(required = false) String reason
    ) {
        inventoryService.releaseReservation(reservationId, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Giải phóng phiếu giữ kho theo mã tham chiếu", description = "Hoàn kho tất cả các mặt hàng gắn với order_id hoặc auction_id (Idempotent)")
    public ApiResponse<Void> releaseReservationsByRef(@Valid @RequestBody ReleaseReservationRequest request) {
        inventoryService.releaseReservationsByRef(request);
        return ApiResponse.ok();
    }

    @PostMapping("/orders/{orderId}/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Giải phóng tồn kho theo mã đơn hàng", description = "Hoàn trả hàng đã giữ về tồn khả dụng khi đơn hàng bị hủy hoặc thanh toán thất bại (Idempotent)")
    public ApiResponse<Void> releaseByOrder(
            @PathVariable UUID orderId,
            @RequestParam(required = false, defaultValue = "ORDER_CANCELLED") String reason
    ) {
        inventoryService.releaseByOrder(orderId, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/events/order-paid")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xử lý sự kiện OrderPaid (Webhook/Sync)", description = "Tiếp nhận sự kiện đơn hàng thanh toán thành công để trừ kho xuất thực tế (Idempotent)")
    public ApiResponse<ReservationResponse> handleOrderPaidEvent(@Valid @RequestBody OrderPaidEventRequest request) {
        ReservationResponse response;
        if (request.reservationId() != null) {
            response = inventoryService.confirmReservation(request.reservationId());
        } else {
            response = inventoryService.commitByOrder(request.orderId());
        }
        return ApiResponse.ok(response);
    }

    @PostMapping("/events/order-payment-failed")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xử lý sự kiện OrderPaymentFailed (Webhook/Sync)", description = "Tiếp nhận sự kiện thanh toán thất bại để giải phóng hàng giữ kho về available_qty (Idempotent)")
    public ApiResponse<Void> handleOrderPaymentFailedEvent(@Valid @RequestBody OrderFailureEventRequest request) {
        String reason = (request.reason() != null && !request.reason().isBlank()) ? request.reason() : "PAYMENT_FAILED";
        if (request.reservationId() != null) {
            inventoryService.releaseReservation(request.reservationId(), reason);
        } else {
            inventoryService.releaseByOrder(request.orderId(), reason);
        }
        return ApiResponse.ok();
    }

    @PostMapping("/events/order-cancelled")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xử lý sự kiện OrderCancelled (Webhook/Sync)", description = "Tiếp nhận sự kiện hủy đơn hàng để giải phóng hàng giữ kho về available_qty (Idempotent)")
    public ApiResponse<Void> handleOrderCancelledEvent(@Valid @RequestBody OrderFailureEventRequest request) {
        String reason = (request.reason() != null && !request.reason().isBlank()) ? request.reason() : "ORDER_CANCELLED";
        if (request.reservationId() != null) {
            inventoryService.releaseReservation(request.reservationId(), reason);
        } else {
            inventoryService.releaseByOrder(request.orderId(), reason);
        }
        return ApiResponse.ok();
    }

    @PostMapping("/reservations/expire-sweeper")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Kích hoạt quét hoàn kho quá hạn thủ công", description = "Quét ngay lập tức các phiếu giữ hàng PENDING đã quá hạn để hoàn trả tồn kho")
    public ApiResponse<Map<String, Object>> sweepExpiredReservations() {
        int count = inventoryService.autoReleaseExpiredReservations();
        return ApiResponse.ok(Map.of("releasedCount", count, "message", "Đã quét và hoàn kho thành công"));
    }

    @PostMapping("/inbound")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Nhập kho hàng hóa", description = "Tăng total_qty cho SKU thuộc quyền quản lý và ghi nhận sổ cái INBOUND (Idempotent theo Idempotency-Key/refId)")
    public ApiResponse<InventoryStockResponse> inboundStock(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StockInboundRequest request
    ) {
        InventoryStockResponse response = inventoryService.inboundStock(request, idempotencyKey);
        return ApiResponse.ok(response);
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Điều chỉnh kiểm kê tồn kho", description = "Điều chỉnh số lượng thực tế tại kho cho SKU thuộc quyền quản lý và ghi sổ cái ADJUSTMENT (Idempotent)")
    public ApiResponse<InventoryStockResponse> adjustStock(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody StockAdjustmentRequest request
    ) {
        InventoryStockResponse response = inventoryService.adjustStock(request, idempotencyKey);
        return ApiResponse.ok(response);
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Tra cứu Sổ cái tồn kho bất biến", description = "Đọc lịch sử biến động kho theo SKU/kho/loại biến động/thời gian; Seller chỉ xem được dữ liệu của SKU mình sở hữu")
    public ApiResponse<List<InventoryLedgerResponse>> getLedger(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) String skuCode,
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) LedgerMovementType movementType,
            @RequestParam(required = false) String refType,
            @RequestParam(required = false) UUID refId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<InventoryLedgerResponse> page = inventoryService.getLedger(
                skuId,
                skuCode,
                warehouseId,
                movementType,
                refType,
                refId,
                from,
                to,
                pageable
        );
        return ApiResponse.ok(page);
    }

    @GetMapping("/reconcile")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Đối soát tồn kho với sổ cái bất biến", description = "Kiểm tra số dư totalQty và reservedQty khớp với sổ cái inventory_ledger và phiếu giữ hàng PENDING")
    public ApiResponse<List<InventoryReconciliationResponse>> reconcileStock(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID warehouseId
    ) {
        return ApiResponse.ok(inventoryService.reconcileStock(skuId, warehouseId));
    }
}
