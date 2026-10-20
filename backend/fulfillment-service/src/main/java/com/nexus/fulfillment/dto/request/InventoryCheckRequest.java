package com.nexus.fulfillment.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Yêu cầu kiểm tra tồn kho hàng loạt cho giỏ hàng / bước checkout.
 *
 * Chức năng: Tiếp nhận danh sách các SKU và số lượng tương ứng để kiểm tra tính sẵn sàng trước khi thanh toán.
 *
 * @param items Danh sách các mục SKU cần kiểm tra (không được rỗng)
 */
@Schema(description = "Yêu cầu kiểm tra tồn kho khả dụng cho danh sách SKU")
public record InventoryCheckRequest(
        @NotEmpty(message = "items list must not be empty")
        @Valid
        @Schema(description = "Danh sách các SKU và số lượng cần kiểm tra", requiredMode = Schema.RequiredMode.REQUIRED)
        List<InventoryCheckItemRequest> items
) {
}
