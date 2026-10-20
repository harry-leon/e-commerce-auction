package com.nexus.fulfillment.controller;

import com.nexus.fulfillment.dto.request.CommitReservationRequest;
import com.nexus.fulfillment.dto.request.CreateReservationRequest;
import com.nexus.fulfillment.dto.request.InventoryCheckRequest;
import com.nexus.fulfillment.dto.request.ReleaseReservationRequest;
import com.nexus.fulfillment.dto.response.ApiResponse;
import com.nexus.fulfillment.dto.response.InventoryCheckResponse;
import com.nexus.fulfillment.dto.response.ReservationResponse;
import com.nexus.fulfillment.dto.response.SkuInventoryResponse;
import com.nexus.fulfillment.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST Controller quản lý tồn kho và phiếu giữ hàng (Fulfillment Service).
 *
 * Chức năng:
 * - Cung cấp toàn bộ các API Contract chuẩn hóa cho chu trình tồn kho (SRS §3.6.1 & §4.2):
 *   1. Kiểm tra tồn kho khả dụng cho danh sách SKU trước khi checkout.
 *   2. Tạo phiếu giữ hàng (Reservation) khi checkout đơn hàng hoặc kết thúc đấu giá.
 *   3. Tra cứu chi tiết phiếu giữ hàng.
 *   4. Xác nhận xuất kho chính thức (Commit) khi thanh toán thành công (InventoryCommitted).
 *   5. Hủy giữ hàng và hoàn kho (Release) khi đơn bị hủy hoặc hết hạn (InventoryReleased).
 *   6. Tra cứu tồn kho tổng và khả dụng của từng SKU.
 *
 * Chuẩn hóa giao tiếp:
 * - Dữ liệu trả về thành công đều được bọc trong Envelope ApiResponse<T>.
 * - Dữ liệu trả về khi lỗi được xử lý qua GlobalExceptionHandler trả về ApiError chuẩn.
 */
@RestController
@RequestMapping("/api/v1/inventory")
@Tag(name = "Inventory", description = "Quản lý tồn kho và phiếu giữ hàng (Fulfillment Service)")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /**
     * Kiểm tra số lượng tồn kho khả dụng cho danh sách các SKU yêu cầu.
     */
    @PostMapping("/check")
    @Operation(
            summary = "Kiểm tra tồn kho khả dụng",
            description = "Tiếp nhận danh sách sku_id, warehouse_id (tùy chọn) và quantity để xác định khả năng đáp ứng đơn hàng."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Kiểm tra thành công",
                    content = @Content(schema = @Schema(implementation = InventoryCheckResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Dữ liệu yêu cầu không hợp lệ (VALIDATION_ERROR)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ApiResponse<InventoryCheckResponse> checkStock(@Valid @RequestBody InventoryCheckRequest request) {
        return ApiResponse.ok(inventoryService.checkStock(request));
    }

    /**
     * Tạo phiếu giữ hàng tồn kho tạm thời (Reservation) khi người dùng checkout đơn hàng.
     */
    @PostMapping("/reservations")
    @Operation(
            summary = "Tạo phiếu giữ hàng (Reservation)",
            description = "Nhận thông tin order_id, danh sách SKU, số lượng yêu cầu và thời gian hết hạn (expires_at) để tạm giữ tồn kho."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "Tạo phiếu giữ hàng thành công",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Dữ liệu yêu cầu không hợp lệ (VALIDATION_ERROR)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Không tìm thấy mã SKU (ERR_SKU_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "Không đủ tồn kho khả dụng (ERR_INSUFFICIENT_STOCK)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ResponseEntity<ApiResponse<ReservationResponse>> createReservation(
            @Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse response = inventoryService.createReservation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    /**
     * Tra cứu chi tiết phiếu giữ hàng theo ID.
     */
    @GetMapping("/reservations/{id}")
    @Operation(
            summary = "Tra cứu phiếu giữ hàng",
            description = "Lấy thông tin chi tiết và trạng thái hiện tại của phiếu giữ hàng theo mã định danh (id)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tra cứu thành công",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Không tìm thấy phiếu giữ hàng (ERR_RESERVATION_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ApiResponse<ReservationResponse> getReservation(@PathVariable UUID id) {
        return ApiResponse.ok(inventoryService.getReservation(id));
    }

    /**
     * Xác nhận xuất kho chính thức (Commit Reservation) khi đơn hàng thanh toán thành công.
     */
    @PostMapping("/reservations/{id}/commit")
    @Operation(
            summary = "Xác nhận xuất kho (Commit Reservation)",
            description = "Chuyển trạng thái phiếu giữ hàng sang CONFIRMED và trừ tổng tồn kho vật lý sau khi thanh toán thành công."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Xác nhận xuất kho thành công",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Trạng thái phiếu giữ hàng không hợp lệ (ERR_INVALID_RESERVATION_STATUS)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Không tìm thấy phiếu giữ hàng (ERR_RESERVATION_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "410",
                    description = "Phiếu giữ hàng đã hết hạn hiệu lực (ERR_RESERVATION_EXPIRED)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ApiResponse<ReservationResponse> commitReservation(
            @PathVariable UUID id,
            @RequestBody(required = false) CommitReservationRequest request) {
        CommitReservationRequest req = request != null ? request : new CommitReservationRequest(null);
        return ApiResponse.ok(inventoryService.commitReservation(id, req));
    }

    /**
     * Hủy giữ hàng và hoàn kho (Release Reservation) khi đơn hàng bị hủy hoặc thanh toán thất bại.
     */
    @PostMapping("/reservations/{id}/release")
    @Operation(
            summary = "Hủy giữ hàng và hoàn kho (Release Reservation)",
            description = "Giải phóng số lượng hàng đang giữ và hoàn trả vào tồn kho khả dụng khi đơn hàng bị hủy hoặc hết hạn."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Hủy giữ hàng thành công",
                    content = @Content(schema = @Schema(implementation = ReservationResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Không thể hủy phiếu đã CONFIRMED (ERR_INVALID_RESERVATION_STATUS)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Không tìm thấy phiếu giữ hàng (ERR_RESERVATION_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ApiResponse<ReservationResponse> releaseReservation(
            @PathVariable UUID id,
            @RequestBody(required = false) ReleaseReservationRequest request) {
        ReleaseReservationRequest req = request != null ? request : new ReleaseReservationRequest(null);
        return ApiResponse.ok(inventoryService.releaseReservation(id, req));
    }

    /**
     * Tra cứu số lượng tồn kho (tổng, đang giữ, hỏng, khả dụng) theo SKU.
     */
    @GetMapping("/sku/{skuId}")
    @Operation(
            summary = "Tra cứu tồn kho theo SKU",
            description = "Lấy thông tin tổng tồn, số lượng đang giữ và số lượng khả dụng của một SKU theo kho."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Tra cứu thành công",
                    content = @Content(schema = @Schema(implementation = SkuInventoryResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "Không tìm thấy SKU trong hệ thống kho (ERR_SKU_NOT_FOUND)",
                    content = @Content(schema = @Schema(implementation = ApiError.class))
            )
    })
    public ApiResponse<SkuInventoryResponse> getSkuInventory(
            @PathVariable UUID skuId,
            @RequestParam(required = false) UUID warehouseId) {
        return ApiResponse.ok(inventoryService.getSkuInventory(skuId, warehouseId));
    }
}
