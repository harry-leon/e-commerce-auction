package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.ReservationRefType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu khóa giữ hàng tồn kho khi Checkout hoặc tạo phiên đấu giá (Atomic Reservation) (SRS §3.6.1).
 *
 * Chức năng:
 * - Nhận danh sách các SKU cần giữ cùng số lượng và thông tin tham chiếu đơn hàng (ORDER) hoặc phiên đấu giá (AUCTION).
 * - Cho phép thiết lập trực tiếp mốc thời gian hết hạn cụ thể (expiresAt) bao quát toàn bộ phiên đấu giá.
 *
 * @param refType        Loại nghiệp vụ tham chiếu (ORDER hoặc AUCTION)
 * @param refId          Mã đơn hàng (order_id) hoặc mã phiên đấu giá (auction_id)
 * @param items          Danh sách SKU và số lượng cần giữ (không rỗng)
 * @param timeoutMinutes Thời gian hết hạn tính theo phút (tùy chọn, mặc định 30 phút theo SRS §2.6)
 * @param expiresAt      Mốc thời gian hết hạn cụ thể của phiếu giữ kho (tùy chọn, ưu tiên hơn timeoutMinutes)
 */
public record CreateReservationRequest(
        @NotNull(message = "refType không được để trống")
        ReservationRefType refType,

        @NotNull(message = "refId không được để trống")
        UUID refId,

        @NotEmpty(message = "Danh sách items cần giữ kho không được để trống")
        @Valid
        List<ReservationItemRequest> items,

        @Min(value = 1, message = "Thời gian giữ kho tối thiểu phải là 1 phút")
        Integer timeoutMinutes,

        Instant expiresAt
) {
    /**
     * Constructor tương thích ngược khi không truyền mốc expiresAt cụ thể.
     */
    public CreateReservationRequest(
            ReservationRefType refType,
            UUID refId,
            List<ReservationItemRequest> items,
            Integer timeoutMinutes
    ) {
        this(refType, refId, items, timeoutMinutes, null);
    }
}
