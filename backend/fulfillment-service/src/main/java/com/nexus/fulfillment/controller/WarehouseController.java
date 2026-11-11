package com.nexus.fulfillment.controller;

import com.nexus.common.response.ApiResponse;
import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.UpdateWarehouseRequest;
import com.nexus.fulfillment.dto.response.InventoryStockResponse;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.enums.WarehouseType;
import com.nexus.fulfillment.service.InventoryService;
import com.nexus.fulfillment.service.WarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API quản lý danh mục Kho hàng (Warehouse) và tra cứu tồn kho theo từng kho.
 *
 * Phân quyền:
 * - Đọc danh sách/chi tiết kho và tồn theo kho: ADMIN, SUPPORT, SELLER (Seller chỉ thấy tồn kho của SKU mình sở hữu).
 * - Tạo mới hoặc cập nhật thông tin kho: chỉ ADMIN hoặc INVENTORY.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/warehouses")
@Tag(name = "Warehouse Management", description = "Quản lý danh mục kho hàng và tồn kho theo kho")
public class WarehouseController {

    private final WarehouseService warehouseService;
    private final InventoryService inventoryService;

    public WarehouseController(WarehouseService warehouseService, InventoryService inventoryService) {
        this.warehouseService = warehouseService;
        this.inventoryService = inventoryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Danh sách kho hàng", description = "Lọc theo trạng thái hoạt động, loại kho và từ khóa tên/mã kho")
    public ApiResponse<List<WarehouseResponse>> listWarehouses(
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) WarehouseType type,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 50, sort = "code") Pageable pageable
    ) {
        Page<WarehouseResponse> page = warehouseService.listWarehouses(activeOnly, type, q, pageable);
        return ApiResponse.ok(page);
    }

    @GetMapping("/{warehouseId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Xem chi tiết kho hàng theo ID")
    public ApiResponse<WarehouseResponse> getWarehouse(@PathVariable UUID warehouseId) {
        return ApiResponse.ok(warehouseService.getWarehouse(warehouseId));
    }

    @GetMapping("/code/{code}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Xem chi tiết kho hàng theo mã code")
    public ApiResponse<WarehouseResponse> getWarehouseByCode(@PathVariable String code) {
        return ApiResponse.ok(warehouseService.getWarehouseByCode(code));
    }

    @GetMapping("/{warehouseId}/inventory")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT', 'SELLER') or hasAnyAuthority('INVENTORY.VIEW', 'INVENTORY.MANAGE')")
    @Operation(summary = "Xem danh sách tồn kho tại một kho cụ thể", description = "Tự động giới hạn theo SKU thuộc quyền sở hữu của Seller nếu caller là SELLER")
    public ApiResponse<List<InventoryStockResponse>> listWarehouseInventory(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) UUID skuId,
            @RequestParam(required = false) String skuCode,
            @RequestParam(required = false) UUID sellerId,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<InventoryStockResponse> page = inventoryService.listInventory(skuId, skuCode, warehouseId, sellerId, pageable);
        return ApiResponse.ok(page);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Tạo kho hàng mới", description = "Chỉ quản trị viên mới có quyền thêm kho hàng")
    public ResponseEntity<ApiResponse<WarehouseResponse>> createWarehouse(
            @Valid @RequestBody CreateWarehouseRequest request
    ) {
        WarehouseResponse created = warehouseService.createWarehouse(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(created));
    }

    @PutMapping("/{warehouseId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVENTORY.MANAGE')")
    @Operation(summary = "Cập nhật thông tin kho hàng", description = "Cập nhật tên, địa chỉ, loại kho và trạng thái hoạt động")
    public ApiResponse<WarehouseResponse> updateWarehouse(
            @PathVariable UUID warehouseId,
            @Valid @RequestBody UpdateWarehouseRequest request
    ) {
        return ApiResponse.ok(warehouseService.updateWarehouse(warehouseId, request));
    }
}
