package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.CreateShipmentRequest;
import com.nexus.fulfillment.dto.request.OrderPaidEventRequest;
import com.nexus.fulfillment.dto.response.ShipmentResponse;
import com.nexus.fulfillment.dto.response.ShipmentTrackingResponse;
import com.nexus.fulfillment.service.ShippingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * REST API Controller quản lý tạo vận đơn và tra cứu hành trình giao hàng thời gian thực (SRS §3.6.2).
 */
@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipment Management", description = "Tạo vận đơn, tiếp nhận OrderPaid và tra cứu hành trình giao hàng")
public class ShipmentController {

    private final ShippingService shippingService;

    public ShipmentController(ShippingService shippingService) {
        this.shippingService = shippingService;
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
