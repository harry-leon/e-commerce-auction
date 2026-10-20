package com.nexus.fulfillment.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Phản hồi cho API kiểm tra tồn kho hàng loạt.
 *
 * Chức năng: Đóng gói kết quả kiểm tra khả năng đáp ứng tồn kho cho toàn bộ danh sách mặt hàng yêu cầu.
 *
 * @param available Trạng thái tổng quát: true nếu TOÀN BỘ các mặt hàng đều đủ tồn khả dụng; false nếu có ít nhất 1 mặt hàng thiếu.
 * @param items     Danh sách chi tiết kết quả từng SKU được kiểm tra.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Phản hồi kết quả kiểm tra tồn kho cho danh sách SKU")
public record InventoryCheckResponse(
        @Schema(description = "Trạng thái tổng thể: true nếu tất cả sản phẩm đều đủ tồn kho", example = "true")
        boolean available,

        @Schema(description = "Chi tiết kết quả kiểm tra từng SKU")
        List<InventoryCheckItemResult> items
) {

    public static InventoryCheckResponse of(List<InventoryCheckItemResult> items) {
        boolean allAvailable = items != null && !items.isEmpty()
                && items.stream().allMatch(InventoryCheckItemResult::available);
        return new InventoryCheckResponse(allAvailable, items != null ? items : List.of());
    }
}
