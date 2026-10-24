package com.nexus.catalog.controller;

import com.nexus.catalog.service.SkuLookupService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** API đọc SKU dùng chung với Cart; nằm dưới route products đã có trong Gateway. */
@RestController
public class SkuLookupController {
    private final SkuLookupService service;
    public SkuLookupController(SkuLookupService service) { this.service = service; }

    /** Giá và trạng thái chỉ đọc; thông tin tồn kho sẽ được lấy từ Fulfillment ở luồng checkout. */
    @Operation(summary = "Lấy SKU snapshot cho giỏ hàng")
    @GetMapping("/api/v1/products/skus/{id}")
    public ApiResponse<SkuLookupService.SkuSnapshot> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }
}
