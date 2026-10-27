package com.nexus.fulfillment.dto.request;

import com.nexus.fulfillment.enums.ReservationRefType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu khóa giữ hàng tồn kho khi Checkout (Atomic Reservation) từ Commerce Service hoặc Auction Service.
 *
 * Chức năng:
 * - Nhận danh sách các SKU cần giữ cùng số lượng và thông tin tham chiếu đơn hàng.
 *
 * @param refType        Loại nghiệp vụ tham chiếu (ORDER hoặc AUCTION)
 * @param refId          Mã đơn hàng (order_id) hoặc mã phiên đấu giá (auction_id)
 * @param items          Danh sách SKU và số lượng cần giữ (không rỗng)
 * @param timeoutMinutes Thời gian hết hạn tính theo phút (tùy chọn, mặc định 30 phút theo SRS §2.6)
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
        Integer timeoutMinutes
) {
}
