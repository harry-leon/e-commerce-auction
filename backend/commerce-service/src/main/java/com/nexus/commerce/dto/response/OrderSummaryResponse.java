package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

/**
 * DTO tóm tắt thông tin đơn hàng dùng cho danh sách lịch sử mua của Buyer và danh sách đơn của Seller.
 *
 * <p>Chức năng:
 * Cung cấp dữ liệu rút gọn tối ưu hiệu năng cho các màn hình danh sách đơn hàng kèm phân trang PageResponse,
 * giúp giảm tải băng thông mạng và tăng tốc độ phản hồi API.
 *
 * <p>Đầu vào và kết quả:
 * Nhận dữ liệu được ánh xạ từ thực thể Order qua OrderMapper, trả về cấu trúc dữ liệu bất biến (record) cho client.
 *
 * <p>Luồng xử lý:
 * Controller tiếp nhận yêu cầu truy vấn phân trang -> OrderService gọi OrderRepository -> OrderMapper chuyển đổi
 * từng entity sang OrderSummaryResponse -> đóng gói vào PageResponse.
 *
 * <p>Quy tắc và lý do:
 * Sử dụng Java 21 record bất biến kết hợp Lombok @Builder để đảm bảo tính an toàn dữ liệu, chống đột biến ngoài ý muốn
 * và tối ưu bộ nhớ heap. Các trường tài chính dùng BigDecimal đảm bảo độ chính xác tiền tệ.
 *
 * @param id Mã định danh duy nhất của đơn hàng (Primary Key, UUID).
 * @param orderNo Mã hiển thị công khai thân thiện (ví dụ: ORD-20261029-001).
 * @param buyerId ID tài khoản người mua (Keycloak User ID).
 * @param sellerId ID tài khoản người bán hoặc chủ gian hàng (Keycloak User ID).
 * @param sourceType Phân loại nguồn gốc đơn hàng (REGULAR: Mua thường, AUCTION: Đấu giá).
 * @param status Trạng thái hiện tại của đơn hàng theo OrderStatus.
 * @param total Tổng tiền chính thức, null nếu chưa xác nhận thu tiền; dùng estimatedTotal để hiển thị dự kiến.
 * @param itemCount Tổng số lượng dòng sản phẩm trong đơn hàng.
 * @param shippingAddress Địa chỉ giao hàng snapshot tại thời điểm đặt hàng.
 * @param createdAt Thời điểm tạo đơn hàng (lưu timestamp UTC).
 * @param fulfillmentStatus Kết quả kho của COD đã thu tiền; REVIEW chặn xử lý giao hàng tự động.
 */
@Builder
public record OrderSummaryResponse(
        UUID id,
        String orderNo,
        UUID buyerId,
        UUID sellerId,
        OrderSourceType sourceType,
        OrderStatus status,
        BigDecimal total,
        Integer itemCount,
        String shippingAddress,
        Instant createdAt,
        BigDecimal estimatedTotal,
        boolean priceFinalized,
        String fulfillmentStatus
) {
}
