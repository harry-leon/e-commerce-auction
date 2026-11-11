package com.nexus.fulfillment.service;

import com.nexus.fulfillment.dto.request.CreateWarehouseRequest;
import com.nexus.fulfillment.dto.request.UpdateWarehouseRequest;
import com.nexus.fulfillment.dto.response.WarehouseResponse;
import com.nexus.fulfillment.enums.WarehouseType;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Giao diện dịch vụ quản lý danh mục kho hàng (Warehouse) phục vụ đa kho cho Seller và Admin.
 */
public interface WarehouseService {

    /**
     * Liệt kê danh sách kho hàng có lọc theo trạng thái hoạt động, loại kho hoặc từ khóa mã/tên.
     */
    Page<WarehouseResponse> listWarehouses(Boolean activeOnly, WarehouseType type, String keyword, Pageable pageable);

    /**
     * Lấy chi tiết một kho hàng theo ID.
     */
    WarehouseResponse getWarehouse(UUID warehouseId);

    /**
     * Lấy chi tiết một kho hàng theo mã nghiệp vụ (ví dụ WH_001).
     */
    WarehouseResponse getWarehouseByCode(String code);

    /**
     * Tạo mới kho hàng vật lý hoặc kho hoàn trả (yêu cầu quyền ADMIN).
     */
    WarehouseResponse createWarehouse(CreateWarehouseRequest request);

    /**
     * Cập nhật thông tin kho hàng hoặc bật/tắt trạng thái hoạt động (yêu cầu quyền ADMIN).
     */
    WarehouseResponse updateWarehouse(UUID warehouseId, UpdateWarehouseRequest request);
}
