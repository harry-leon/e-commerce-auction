package com.nexus.reputation.client;

import java.util.UUID;

/**
 * Cổng xác thực đơn hàng sang commerce-service trước khi ghi nhận đánh giá.
 * Giữ ranh giới Database-per-Service: reputation-service chỉ đọc thông tin đơn qua HTTP API.
 */
public interface OrderVerificationClient {

    VerifiedOrder verifyOrder(UUID orderId);

    /** Thông tin tối thiểu của đơn hàng phục vụ đối chiếu quyền đánh giá và trạng thái giao hàng. */
    record VerifiedOrder(UUID id, UUID buyerId, UUID sellerId, String status) {
    }
}
