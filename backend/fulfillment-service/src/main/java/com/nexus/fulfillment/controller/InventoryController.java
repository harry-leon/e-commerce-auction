package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.request.SkuAvailabilityCheckRequest;
import com.nexus.fulfillment.dto.request.StockAdjustmentRequest;
import com.nexus.fulfillment.dto.request.StockInboundRequest;
import com.nexus.fulfillment.dto.response.InventoryLedgerResponse;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuAvailabilityResponse;
import com.nexus.fulfillment.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller phục vụ quản lý tồn kho cấp SKU và khóa giữ hàng khi Checkout (SRS §3.6.1).
 *
 * Chức năng:
 * - Cung cấp API tra cứu tồn kho, kiểm tra tính khả dụng của SKU cho giỏ hàng.
 * - Nhận yêu cầu giữ kho nguyên tử (Atomic Reservation) từ Commerce Service khi người dùng checkout.
 * - Cung cấp API xác nhận xuất kho (Confirm), giải phóng hoàn kho (Release), và kích hoạt hoàn kho quá hạn.
 * - Cung cấp API tra cứu Sổ cái tồn kho bất biến (Immutable Inventory Ledger).
 *
 * Quy tắc và lý do:
 * - Chuẩn hóa toàn bộ phản hồi theo cấu trúc ApiResponse v2 (Envelope Pattern).
 * - Sử dụng @Valid để kích hoạt kiểm tra tính hợp lệ của tham số đầu vào.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory Management", description = "Quản lý tồn kho cấp SKU và giữ hàng Checkout với kiểm soát đồng thời")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
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

    @GetMapping("/sku/{skuId}/all")
    @Operation(summary = "Xem tồn kho SKU trên mọi kho hàng", description = "Trả về danh sách trạng thái tồn của SKU trên tất cả các kho")
    public ApiResponse<List<InventoryStockResponse>> getAllStockBySku(@PathVariable UUID skuId) {
        List<InventoryStockResponse> response = inventoryService.getAllStockBySku(skuId);
        return ApiResponse.ok(response);
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
    @Operation(summary = "Khóa giữ hàng khi Checkout (Atomic Reservation)", description = "Khóa tạm giữ hàng tồn kho cho đơn hàng với Concurrency Control, ngăn chặn bán vượt tồn")
    public ResponseEntity<ApiResponse<ReservationResponse>> reserveStock(
            @Valid @RequestBody CreateReservationRequest request
    ) {
        ReservationResponse response = inventoryService.reserveStock(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/reservations/{reservationId}/confirm")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xác nhận phiếu giữ kho (Commit/Outbound)", description = "Chốt xuất kho vật lý sau khi đơn hàng thanh toán thành công")
    public ApiResponse<ReservationResponse> confirmReservation(@PathVariable UUID reservationId) {
        ReservationResponse response = inventoryService.confirmReservation(reservationId);
        return ApiResponse.ok(response);
    }

    @PostMapping("/reservations/{reservationId}/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Giải phóng phiếu giữ hàng", description = "Hoàn trả số lượng đang giữ về tồn khả dụng khi người dùng hủy đơn hoặc thanh toán lỗi")
    public ApiResponse<Void> releaseReservation(
            @PathVariable UUID reservationId,
            @RequestParam(required = false) String reason
    ) {
        inventoryService.releaseReservation(reservationId, reason);
        return ApiResponse.ok();
    }

    @PostMapping("/release")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Giải phóng phiếu giữ kho theo mã tham chiếu đơn hàng", description = "Hoàn kho tất cả các mặt hàng gắn với order_id hoặc auction_id")
    public ApiResponse<Void> releaseReservationsByRef(@Valid @RequestBody ReleaseReservationRequest request) {
        inventoryService.releaseReservationsByRef(request);
        return ApiResponse.ok();
    }

    @PostMapping("/reservations/expire-sweeper")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Kích hoạt quét hoàn kho quá hạn thủ công", description = "Quét ngay lập tức các phiếu giữ hàng PENDING đã quá 30 phút để hoàn trả tồn kho")
    public ApiResponse<Map<String, Object>> sweepExpiredReservations() {
        int count = inventoryService.autoReleaseExpiredReservations();
        return ApiResponse.ok(Map.of("releasedCount", count, "message", "Đã quét và hoàn kho thành công"));
    }

    @PostMapping("/inbound")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Nhập kho hàng hóa", description = "Tăng total_qty cho SKU và ghi nhận vào sổ cái INBOUND")
    public ApiResponse<InventoryStockResponse> inboundStock(@Valid @RequestBody StockInboundRequest request) {
        InventoryStockResponse response = inventoryService.inboundStock(request);
        return ApiResponse.ok(response);
    }

    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Điều chỉnh kiểm kê tồn kho", description = "Điều chỉnh số lượng thực tế tại kho và ghi sổ cái ADJUSTMENT")
    public ApiResponse<InventoryStockResponse> adjustStock(@Valid @RequestBody StockAdjustmentRequest request) {
        InventoryStockResponse response = inventoryService.adjustStock(request);
        return ApiResponse.ok(response);
    }

    @GetMapping("/ledger")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAuthority('INVENTORY.VIEW')")
    @Operation(summary = "Tra cứu Sổ cái tồn kho bất biến", description = "Đọc lịch sử toàn bộ các biến động kho (INBOUND, OUTBOUND, RESERVE, RELEASE, ADJUSTMENT)")
    public ApiResponse<List<InventoryLedgerResponse>> getLedger(
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) UUID refId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<InventoryLedgerResponse> page = inventoryService.getLedger(skuId, refId, pageable);
        return ApiResponse.ok(page);
    }
}
