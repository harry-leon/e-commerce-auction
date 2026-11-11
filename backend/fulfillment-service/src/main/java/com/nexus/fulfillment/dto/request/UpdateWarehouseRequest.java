package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.WarehouseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu cập nhật thông tin kho hàng hoặc bật/tắt trạng thái hoạt động (ADMIN / INVENTORY.MANAGE).
 *
 * @param name    Tên kho mới
 * @param address Địa chỉ kho mới
 * @param type    Loại kho (tùy chọn)
 * @param active  Trạng thái hoạt động (tùy chọn)
 */
public record UpdateWarehouseRequest(
        @NotBlank(message = "Tên kho không được để trống")
        @Size(max = 255, message = "Tên kho tối đa 255 ký tự")
        String name,

        @NotBlank(message = "Địa chỉ kho không được để trống")
        @Size(max = 500, message = "Địa chỉ kho tối đa 500 ký tự")
        String address,

        WarehouseType type,

        Boolean active
) {
}
