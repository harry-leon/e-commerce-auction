package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/**
 * Yêu cầu chuyển tiếp trạng thái giữ hàng từ phiên đấu giá sang Đơn hàng của người thắng (SRS §3.6.1).
 *
 * Chức năng:
 * - Đảm bảo không xảy ra hiện tượng "Reserve kép" (Double Reservation): Khi chốt người thắng cuộc,
 *   chuyển đổi mã tham chiếu từ auction_id sang order_id mà không cần nhả kho rồi giữ lại từ đầu.
 * - Cập nhật thời hạn thanh toán đơn hàng mới (mặc định 24 giờ kể từ thời điểm chốt).
 *
 * Đầu vào và kết quả:
 * - Đầu vào: orderId (mã đơn hàng Commerce), paymentDeadlineMinutes (thời hạn thanh toán tính theo phút), paymentDeadline (mốc cụ thể).
 * - Kết quả: Phiếu giữ hàng chuyển đổi refType sang ORDER, refId sang orderId và cập nhật expiresAt mới.
 * - Tác dụng phụ: Không thay đổi reserved_qty hoặc total_qty trong bảng inventories, không tạo thời điểm hàng quay lại available.
 *
 * Quy tắc và lý do:
 * - Bảo đảm tính Idempotent: Nếu gọi lại với cùng auctionId và orderId, trả về kết quả thành công ngay lập tức.
 * - Ngăn chặn tình trạng khách mua ngay (Buy Now) nhảy vào cướp mất sản phẩm của Winner trong tích tắc chuyển đổi.
 *
 * @param orderId                Mã đơn hàng mới được tạo trong commerce-service cho Winner
 * @param paymentDeadlineMinutes Thời hạn thanh toán tính theo phút (mặc định 1440 phút = 24 giờ)
 * @param paymentDeadline        Mốc thời gian hết hạn thanh toán cụ thể (tùy chọn)
 */
public record TransitionAuctionToOrderRequest(
        @NotNull(message = "orderId không được để trống")
        UUID orderId,

        @Min(value = 1, message = "Thời hạn thanh toán tối thiểu là 1 phút")
        Integer paymentDeadlineMinutes,

        Instant paymentDeadline
) {
}

