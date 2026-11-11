package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.WarehouseType;
import java.util.UUID;

/**
 * Phản hồi thông tin kho hàng vật lý phục vụ quản trị đa kho của Seller và Admin.
 *
 * @param id      Mã định danh kho
 * @param code    Mã nghiệp vụ duy nhất (vd: WH_001, WH_002)
 * @param name    Tên hiển thị kho hàng
 * @param address Địa chỉ kho hàng
 * @param type    Loại kho (STANDARD, BONDED, RETURN, CROSS_DOCK)
 * @param active  Trạng thái hoạt động của kho
 */
public record WarehouseResponse(
        UUID id,
        String code,
        String name,
        String address,
        WarehouseType type,
        boolean active
) {
}
