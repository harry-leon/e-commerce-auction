package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.ShippingQuoteRequest;
import com.nexus.fulfillment.dto.response.CarrierInfoResponse;
import com.nexus.fulfillment.dto.response.CarrierResilienceStatus;
import com.nexus.fulfillment.dto.response.ShippingQuoteResponse;
import com.nexus.fulfillment.service.ShippingService;
import com.nexus.fulfillment.shipping.adapter.resilience.CarrierResilienceManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API Controller phục vụ báo giá phí vận chuyển, tra cứu hãng hỗ trợ và giám sát Circuit Breaker (SRS §3.6.2, KH-S3-2).
 */
@RestController
@RequestMapping("/api/v1/shipping")
@Tag(name = "Shipping Quotation & Resilience", description = "Báo giá cước vận chuyển đa hãng (GHN, GHTK), hãng hỗ trợ và trạng thái Circuit Breaker")
public class ShippingController {

    private final ShippingService shippingService;
    private final CarrierResilienceManager resilienceManager;

    public ShippingController(
            ShippingService shippingService,
            CarrierResilienceManager resilienceManager) {
        this.shippingService = shippingService;
        this.resilienceManager = resilienceManager;
    }

    @PostMapping("/quotes")
    @PreAuthorize("hasAnyRole('ADMIN', 'BUYER', 'SELLER', 'SUPPORT') or hasAuthority('SHIPPING.QUOTE')")
    @Operation(
            summary = "Báo giá phí vận chuyển",
            description = "Yêu cầu báo giá từ các hãng hỗ trợ (GHN, GHTK) qua Circuit Breaker & Retry, áp dụng quy tắc FREE_SHIPPING_THRESHOLD")
    public ApiResponse<ShippingQuoteResponse> quoteShipping(@Valid @RequestBody ShippingQuoteRequest request) {
        return ApiResponse.ok(shippingService.quoteShipping(request));
    }

    @GetMapping("/carriers")
    @PreAuthorize("isAuthenticated()")
    @Operation(
            summary = "Danh sách hãng vận chuyển hỗ trợ",
            description = "Trả về các hãng trong SUPPORTED_CARRIERS và hãng mặc định DEFAULT_CARRIER")
    public ApiResponse<List<CarrierInfoResponse>> listCarriers() {
        return ApiResponse.ok(shippingService.listCarriers());
    }

    @GetMapping("/resilience")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT') or hasAuthority('SHIPPING.RESILIENCE')")
    @Operation(
            summary = "Trạng thái Circuit Breaker và khả năng chịu lỗi từng hãng",
            description = "Giám sát trạng thái ngắt mạch (CLOSED, OPEN, HALF_OPEN), số lần lỗi, timeout và retry limit của các carrier")
    public ApiResponse<List<CarrierResilienceStatus>> getResilienceStatus() {
        return ApiResponse.ok(resilienceManager.getResilienceStatuses());
    }
}
