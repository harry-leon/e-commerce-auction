package com.nexus.commerce.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.Builder;

/**
 * DTO biểu diễn một dòng sản phẩm snapshot trong đơn hàng.
 *
 * <p>Chức năng:
 * Cung cấp thông tin chi tiết từng sản phẩm được đóng băng dữ liệu tại thời điểm đặt hàng thành công.
 * Đảm bảo giá trị sản phẩm, mã biến thể SKU, số lượng và giá mua không bị thay đổi ngay cả khi
 * Catalog Service cập nhật giá bán hoặc xóa sản phẩm sau này.
 *
 * <p>Đầu vào và kết quả:
 * Nhận dữ liệu từ thực thể OrderItem được OrderMapper ánh xạ thành record bất biến để hiển thị trong OrderDetailResponse.
 *
 * <p>Luồng xử lý:
 * OrderService truy xuất Order kèm danh sách OrderItem -> OrderMapper chuyển đổi từng OrderItem sang OrderItemResponse.
 *
 * <p>Quy tắc và lý do:
 * Ràng buộc số lượng quantity phải lớn hơn 0 và thành tiền subtotal được tính bằng unitPrice * quantity
 * với scale 2 và RoundingMode.HALF_UP chuẩn kế toán.
 *
 * @param id Mã định danh của dòng sản phẩm (Primary Key, UUID).
 * @param skuId ID của biến thể sản phẩm từ Catalog Service.
 * @param productName Tên sản phẩm được lưu snapshot tại thời điểm mua.
 * @param skuCode Mã SKU của biến thể sản phẩm snapshot.
 * @param unitPrice Đơn giá chính thức từ attempt thành công, null khi chưa thu tiền.
 * @param quantity Số lượng sản phẩm đặt mua (quantity > 0).
 * @param subtotal Thành tiền của dòng sản phẩm (unitPrice * quantity).
 */
@Builder
public record OrderItemResponse(
        UUID id,
        UUID skuId,
        String productName,
        String skuCode,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal,
        BigDecimal estimatedUnitPrice,
        BigDecimal estimatedSubtotal
) {
}
