package com.nexus.catalog.controller;

import com.nexus.catalog.dto.request.PricingRuleRequest;
import com.nexus.catalog.dto.response.PricePreviewResponse;
import com.nexus.catalog.dto.response.PricingRuleResponse;
import com.nexus.catalog.service.PricingRuleService;
import com.nexus.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API quản lý và tra cứu quy tắc định giá động (/api/v1/pricing-rules).
 * Public/Seller dùng endpoint GET active và /preview; Admin quản trị đầy đủ CRUD quy tắc.
 */
@RestController
@RequestMapping("/api/v1/pricing-rules")
@Validated
@Tag(name = "Pricing Rules", description = "Dynamic SKU pricing rules & preview calculation")
public class PricingRuleController {

    private final PricingRuleService pricingRuleService;

    public PricingRuleController(PricingRuleService pricingRuleService) {
        this.pricingRuleService = pricingRuleService;
    }

    /** Lấy các quy tắc đang bật và còn hiệu lực. */
    @Operation(summary = "Danh sách quy tắc giá đang hiệu lực")
    @GetMapping
    public ApiResponse<List<PricingRuleResponse>> listActive() {
        return ApiResponse.ok(pricingRuleService.listActive());
    }

    /** Lấy toàn bộ quy tắc (kể cả đã tắt/hết hạn) cho quản trị viên. */
    @Operation(summary = "Toàn bộ quy tắc giá (Admin)")
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ADMIN')")
    public ApiResponse<List<PricingRuleResponse>> listAll() {
        return ApiResponse.ok(pricingRuleService.listAll());
    }

    /** Xem trước giá bán động từ originalPrice và categoryId. */
    @Operation(summary = "Xem trước giá bán động theo giá nhập tối thiểu")
    @GetMapping("/preview")
    public ApiResponse<PricePreviewResponse> preview(
            @RequestParam @NotNull @DecimalMin("0") BigDecimal originalPrice,
            @RequestParam(required = false) UUID categoryId) {
        return ApiResponse.ok(pricingRuleService.preview(originalPrice, categoryId));
    }

    /** Tạo quy tắc định giá mới (chỉ Admin). */
    @Operation(summary = "Tạo quy tắc định giá mới (Admin)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ADMIN')")
    public ApiResponse<PricingRuleResponse> create(@Valid @RequestBody PricingRuleRequest request) {
        return ApiResponse.ok(pricingRuleService.create(request));
    }

    /** Cập nhật quy tắc định giá theo ID (chỉ Admin). */
    @Operation(summary = "Cập nhật quy tắc định giá (Admin)")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ADMIN')")
    public ApiResponse<PricingRuleResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody PricingRuleRequest request) {
        return ApiResponse.ok(pricingRuleService.update(id, request));
    }

    /** Xóa quy tắc định giá theo ID (chỉ Admin). */
    @Operation(summary = "Xóa quy tắc định giá (Admin)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ADMIN')")
    public void delete(@PathVariable UUID id) {
        pricingRuleService.delete(id);
    }
}
