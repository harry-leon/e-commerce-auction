package com.nexus.fulfillment.dto.request;

import jakarta.validation.constraints.Min;
import java.time.Instant;

/**
 * Yêu cầu gia hạn thời gian giữ kho khi phiên đấu giá kích hoạt Anti-sniping (SRS §2.6 & §3.6.1).
 *
 * Chức năng:
 * - Tiếp nhận tín hiệu gia hạn khi phiên đấu giá bị kéo dài do Anti-sniping (ANTI_SNIPING_EXTENSION_MINUTES = 5).
 * - Tự động kéo dài thời hạn hết hạn của phiếu giữ kho tương ứng, ngăn chặn việc kho bị giải phóng nhầm khi phiên vẫn đang mở.
 *
 * Đầu vào và kết quả:
 * - Đầu vào: extensionMinutes (số phút gia hạn thêm) hoặc newEndTime (mốc kết thúc mới).
 * - Kết quả: Cập nhật expires_at của phiếu giữ kho AUCTION tương ứng.
 *
 * Quy tắc và lý do:
 * - Nếu không truyền extensionMinutes và newEndTime, hệ thống áp dụng cấu hình mặc định (5 phút).
 * - Mốc hết hạn mới bắt buộc phải lớn hơn mốc hết hạn hiện tại của phiếu giữ hàng.
 *
 * @param extensionMinutes Số phút gia hạn thêm (tùy chọn, mặc định 5 phút theo SRS §2.6)
 * @param newEndTime       Mốc thời gian kết thúc mới của phiên (tùy chọn, nếu truyền sẽ gán trực tiếp làm expires_at)
 */
public record ExtendAuctionReservationRequest(
        @Min(value = 1, message = "Thời gian gia hạn tối thiểu là 1 phút")
        Integer extensionMinutes,

        Instant newEndTime
) {
}

