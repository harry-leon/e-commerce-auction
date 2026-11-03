package com.nexus.fulfillment.dto.response;

import com.nexus.fulfillment.enums.ReservationRefType;
import com.nexus.fulfillment.enums.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phản hồi kết quả đặt giữ kho thành công cho một đơn hàng hoặc phiên đấu giá (SRS §3.6.1).
 *
 * @param refType    Loại tham chiếu (ORDER/AUCTION)
 * @param refId      Mã tham chiếu đơn hàng / phiên đấu giá
 * @param status     Trạng thái tổng hợp của các phiếu giữ hàng
 * @param items      Danh sách các phiếu giữ hàng chi tiết cho từng SKU
 * @param expiresAt  Thời điểm hết hạn của các phiếu
 */
public record ReservationResponse(
        ReservationRefType refType,
        UUID refId,
        ReservationStatus status,
        List<ReservationItemResponse> items,
        Instant expiresAt
) {
}
