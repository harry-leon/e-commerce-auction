package com.nexus.catalog.controller;

import com.nexus.catalog.service.SkuLookupService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * API đọc thông tin SKU Catalog theo skuId dùng chung cho Fulfillment (xác thực quyền sở hữu trước nhập kho)
 * và Commerce (xác thực giá động khi kiểm tra giỏ hàng/checkout).
 */
@RestController
public class SkuLookupController {
    private final SkuLookupService service;

    public SkuLookupController(SkuLookupService service) {
        this.service = service;
    }

    /** Trả snapshot SKU từ Catalog (bao gồm productId, sellerId, originalPrice, price động; không gồm skuCode kho). */
    @Operation(summary = "Lấy thông tin SKU Catalog theo skuId")
    @GetMapping({"/api/v1/skus/{id}", "/api/v1/products/skus/{id}"})
    public ApiResponse<SkuLookupService.SkuSnapshot> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }
}
