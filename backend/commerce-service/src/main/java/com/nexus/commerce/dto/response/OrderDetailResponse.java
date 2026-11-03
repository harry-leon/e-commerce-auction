package com.nexus.commerce.dto.response;

import com.nexus.commerce.enums.OrderSourceType;
import com.nexus.commerce.enums.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

/**
 * DTO chi tiết toàn diện của đơn hàng, phục vụ màn hình xem chi tiết đơn (OrderDetailView).
 *
 * <p>Chức năng:
 * Cung cấp toàn bộ bức tranh dữ liệu của một đơn hàng: các chỉ số tài chính (tạm tính, phí vận chuyển, giảm giá,
 * tổng tiền), địa chỉ giao nhận hàng, danh sách các mặt hàng snapshot và dòng thời gian lịch sử trạng thái kiểm toán.
 *
 * <p>Đầu vào và kết quả:
 * Nhận dữ liệu tổng hợp từ Order, OrderItem và OrderStatusHistory qua OrderMapper, trả về cấu trúc đối tượng hoàn chỉnh cho API response.
 *
 * <p>Luồng xử lý:
 * Controller tiếp nhận yêu cầu GET /orders/{orderId} -> OrderService xác thực quyền truy cập -> OrderMapper
 * tổng hợp entity và quan hệ con thành OrderDetailResponse -> trả về ApiResponse&lt;OrderDetailResponse&gt;.
 *
 * <p>Quy tắc và lý do:
 * Tuân thủ bảo mật đa người dùng (Multi-tenant Ownership): Chỉ cho phép chủ sở hữu đơn (Buyer), chủ gian hàng (Seller)
 * hoặc Quản trị viên (Admin) xem chi tiết. Các trường danh sách items và statusHistory luôn trả về danh sách không null
 * (danh sách rỗng nếu không có dữ liệu) để client an toàn khi render UI.
 *
 * @param id Mã định danh duy nhất của đơn hàng (UUID).
 * @param orderNo Mã tham chiếu hiển thị công khai (ví dụ: ORD-20261029-001).
 * @param buyerId ID tài khoản người mua (Keycloak User ID).
 * @param sellerId ID tài khoản người bán (Keycloak User ID).
 * @param sourceType Nguồn đơn hàng (REGULAR: Đặt thường, AUCTION: Đấu giá).
 * @param auctionId ID phiên đấu giá nếu đơn hàng xuất phát từ đấu giá (có thể null).
 * @param voucherId ID mã giảm giá áp dụng cho đơn hàng (có thể null).
 * @param status Trạng thái hiện tại của đơn hàng (OrderStatus).
 * @param subtotal Tổng tiền hàng trước chiết khấu và phí vận chuyển.
 * @param shippingFee Cước phí vận chuyển thực tế tính cho đơn hàng.
 * @param discount Số tiền được giảm trừ qua voucher hoặc khuyến mãi.
 * @param total Tổng số tiền thanh toán cuối cùng của đơn hàng.
 * @param shippingAddress Địa chỉ giao nhận hàng snapshot chi tiết.
 * @param createdAt Thời điểm đặt đơn hàng thành công (UTC).
 * @param items Danh sách các mặt hàng snapshot đã mua trong đơn hàng.
 * @param statusHistory Dòng thời gian lịch sử các bước chuyển trạng thái của đơn hàng.
 */
@Builder
public record OrderDetailResponse(
        UUID id,
        String orderNo,
        UUID buyerId,
        UUID sellerId,
        OrderSourceType sourceType,
        UUID auctionId,
        UUID voucherId,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal shippingFee,
        BigDecimal discount,
        BigDecimal total,
        String shippingAddress,
        Instant createdAt,
        List<OrderItemResponse> items,
        List<OrderStatusHistoryResponse> statusHistory
) {
}
