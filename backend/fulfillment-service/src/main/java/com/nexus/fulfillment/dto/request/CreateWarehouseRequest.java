package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.WarehouseType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Yêu cầu tạo mới kho hàng vật lý hoặc kho hoàn trả (ADMIN / INVENTORY.MANAGE).
 *
 * @param code    Mã kho duy nhất (tối đa 30 ký tự, tự chuẩn hóa in hoa)
 * @param name    Tên kho hàng
 * @param address Địa chỉ kho hàng
 * @param type    Loại kho (STANDARD, BONDED, RETURN, CROSS_DOCK; mặc định STANDARD)
 * @param active  Trạng thái hoạt động ban đầu (mặc định true)
 */
public record CreateWarehouseRequest(
        @NotBlank(message = "Mã kho không được để trống")
        @Size(max = 30, message = "Mã kho tối đa 30 ký tự")
        String code,

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
