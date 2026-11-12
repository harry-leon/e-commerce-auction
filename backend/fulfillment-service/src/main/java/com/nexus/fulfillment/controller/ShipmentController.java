package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.CarrierWebhookRequest;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.OrderPaidEventRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.service.ShippingService;
import com.nexus.fulfillment.service.WarehouseFulfillmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller quản lý tạo vận đơn, xử lý callback/polling và thao tác kho Pick/Pack/Ship (SRS §3.6.1, §3.6.2, KH-S3-2).
 *
 * Chức năng:
 * - Tiếp nhận OrderPaid để tạo vận đơn idempotent theo DEFAULT_CARRIER hoặc hãng được chọn.
 * - Nhận webhook callback từ đối tác giao vận (GHN, GHTK) theo cơ chế idempotent dựa trên eventId/transactionId.
 * - Kích hoạt thủ công polling cập nhật hành trình cho các vận đơn active.
 * - Điều phối quy trình xử lý vật lý tại kho: Lấy hàng (Pick) -> Đóng gói (Pack) -> Xuất kho (Ship).
 * - Kết nối hành động Ship với việc trừ kho và ghi sổ cái tồn kho bất biến (inventory_ledger).
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipment & Warehouse Fulfillment Management", description = "Tạo vận đơn, webhook callback, tra cứu và quy trình Pick/Pack/Ship")
public class ShipmentController {

    private final ShippingService shippingService;
    private final WarehouseFulfillmentService warehouseFulfillmentService;

    public ShipmentController(
            ShippingService shippingService,
            WarehouseFulfillmentService warehouseFulfillmentService) {
        this.shippingService = shippingService;
        this.warehouseFulfillmentService = warehouseFulfillmentService;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.CREATE_SHIPMENT')")
    @Operation(
            summary = "Tạo vận đơn mới",
            description = "Tạo vận đơn qua Carrier Adapter, lưu mã tracking và phát sự kiện ShipmentCreated")
    public ResponseEntity<ApiResponse<ShipmentResponse>> createShipment(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateShipmentRequest request
    ) {
        ShipmentResponse response = shippingService.createShipment(request, idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @PostMapping("/events/order-paid")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Tạo vận đơn tự động từ sự kiện OrderPaid",
            description = "Tiếp nhận OrderPaid để tạo vận đơn idempotent theo DEFAULT_CARRIER hoặc quy tắc hãng")
    public ApiResponse<ShipmentResponse> handleOrderPaid(@Valid @RequestBody OrderPaidEventRequest request) {
        return ApiResponse.ok(shippingService.createShipmentForPaidOrder(request.orderId()));
    }

    @PostMapping("/callbacks/{carrierCode}")
    @Operation(
            summary = "Tiếp nhận webhook callback từ đối tác giao vận cụ thể",
            description = "Nhận cập nhật hành trình từ carrier (GHN/GHTK), deduplicate idempotent theo eventId và phát domain events tương ứng")
    public ApiResponse<ShipmentResponse> handleCarrierCallback(
            @PathVariable String carrierCode,
            @Valid @RequestBody CarrierWebhookRequest request
    ) {
        return ApiResponse.ok(shippingService.processCarrierWebhook(carrierCode, request));
    }

    @PostMapping("/callbacks")
    @Operation(
            summary = "Tiếp nhận webhook callback tổng quát",
            description = "Xử lý callback định dạng chung khi carrierCode nằm trong payload")
    public ApiResponse<ShipmentResponse> handleGenericCallback(
            @Valid @RequestBody CarrierWebhookRequest request
    ) {
        return ApiResponse.ok(shippingService.processCarrierWebhook(request.carrierCode(), request));
    }

    @PostMapping("/poll")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAuthority('SHIPPING.POLL')")
    @Operation(
            summary = "Kích hoạt thủ công polling hành trình vận đơn active",
            description = "Quét các vận đơn ở trạng thái CREATED, PENDING, PICKING, PACKED, PICKED_UP, SHIPPED, IN_TRANSIT để đồng bộ trạng thái mới")
    public ApiResponse<Map<String, Object>> triggerPolling() {
        int polled = shippingService.pollActiveShipments();
        return ApiResponse.ok(Map.of("message", "Polling completed successfully", "updatedCount", polled));
    }

    @PostMapping("/{shipmentId}/pick")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER', 'SUPPORT') or hasAuthority('FULFILLMENT.PICK')")
    @Operation(
            summary = "Bắt đầu lấy hàng từ kệ kho (Pick Process)",
            description = "Chuyển trạng thái vận đơn sang PICKING khi nhân viên bắt đầu lấy hàng trên kệ")
    public ApiResponse<ShipmentResponse> pickShipment(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(warehouseFulfillmentService.pick(shipmentId));
    }

    @PostMapping("/{shipmentId}/pack")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER', 'SUPPORT') or hasAuthority('FULFILLMENT.PACK')")
    @Operation(
            summary = "Đóng gói hoàn tất kiện hàng (Pack Process)",
            description = "Chuyển trạng thái vận đơn sang PACKED sau khi kiểm đếm và đóng gói xong")
    public ApiResponse<ShipmentResponse> packShipment(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(warehouseFulfillmentService.pack(shipmentId));
    }

    @PostMapping("/{shipmentId}/ship")
    @PreAuthorize("hasAnyRole('ADMIN', 'SELLER', 'SUPPORT') or hasAuthority('FULFILLMENT.SHIP')")
    @Operation(
            summary = "Xuất kho bàn giao vận chuyển (Ship Process)",
            description = "Chuyển trạng thái sang SHIPPED, trừ kho thực tế (reserved_qty -> shipped/committed), ghi sổ cái inventory_ledger và phát sự kiện miền")
    public ApiResponse<ShipmentResponse> shipShipment(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(warehouseFulfillmentService.ship(shipmentId));
    }

    @GetMapping("/{shipmentId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'BUYER', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    @Operation(summary = "Xem chi tiết vận đơn theo ID")
    public ApiResponse<ShipmentResponse> getShipment(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(shippingService.getShipment(shipmentId));
    }

    @GetMapping("/order/{orderId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'BUYER', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    @Operation(summary = "Xem chi tiết vận đơn theo mã đơn hàng (orderId)")
    public ApiResponse<ShipmentResponse> getShipmentByOrderId(@PathVariable UUID orderId) {
        return ApiResponse.ok(shippingService.getShipmentByOrderId(orderId));
    }

    @GetMapping("/{shipmentId}/tracking")
    @PreAuthorize("hasAnyRole('ADMIN', 'BUYER', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    @Operation(
            summary = "Tra cứu hành trình vận đơn thời gian thực",
            description = "Gọi Carrier Adapter để đồng bộ trạng thái mới nhất và trả về lịch sử vận đơn")
    public ApiResponse<ShipmentTrackingResponse> trackShipment(@PathVariable UUID shipmentId) {
        return ApiResponse.ok(shippingService.trackShipment(shipmentId));
    }

    @GetMapping("/tracking/{trackingNumber}")
    @PreAuthorize("hasAnyRole('ADMIN', 'BUYER', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.VIEW_SHIPMENT')")
    @Operation(summary = "Tra cứu hành trình vận đơn theo mã tracking")
    public ApiResponse<ShipmentTrackingResponse> trackByTrackingNumber(@PathVariable String trackingNumber) {
        return ApiResponse.ok(shippingService.trackByTrackingNumber(trackingNumber));
    }
}
